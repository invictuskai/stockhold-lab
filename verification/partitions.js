// Logical stock partitions under concurrency: one SKU sold from several partitions at once, a mid-sale top-up
// transfer, a partition closed and its remainder returned while reservations are still in flight, and purchase
// limits in all three scopes. Verifies per-dimension and per-SKU invariants, limits, and quota bookkeeping.
'use strict';
const crypto = require('crypto');
const L = require('./lib');

const SHOP = 1;
const SHOE = 100, SOCKS = 200;   // two SKUs
const DEFAULT = 0, P_A = 7, P_B = 8; // partition 0 = default; 7 and 8 could be two marketing campaigns upstream
const REQ = Number(process.env.REQ || 20000);
const BUYERS = Number(process.env.BUYERS || 4000);
const CONCURRENCY = Number(process.env.CONC || 64);
const TTL = 5, GRACE = 1;
const INITIAL = { [SHOE]: 2000, [SOCKS]: 500 };
const LIMITS = { A_SHOE: 1, B_SHOE: 2, B_TOTAL: 2, SHOE_ALL: 3 };

const tally = {};
const inc = (k, n = 1) => (tally[k] = (tally[k] || 0) + n);
const key = (item, part) => ({ shop: SHOP, item, part });

(async () => {
  const reservePool = L.makePool(32), criticalPool = L.makePool(16), bgPool = L.makePool(6);
  const setup = await L.conn();
  const [[dl0]] = await setup.query("SELECT COUNT c FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'");

  // ---- setup: default stock, two partitions, transfers in, limit rules, pre-warm ----
  await L.resetAll(setup, SHOP);
  for (const item of [SHOE, SOCKS]) await L.txn(bgPool, (c) => L.initializeTx(c, { shop: SHOP, item, part: DEFAULT, qty: INITIAL[item], opId: L.uuid() }));
  await L.txn(bgPool, (c) => L.createPartitionTx(c, SHOP, P_A, 'campaign-A'));
  await L.txn(bgPool, (c) => L.createPartitionTx(c, SHOP, P_B, 'campaign-B'));
  await L.txn(bgPool, (c) => L.transferTx(c, { shop: SHOP, item: SHOE, src: DEFAULT, dst: P_A, qty: 600, opId: L.uuid(), dstMode: 'BATCHED' }));
  await L.txn(bgPool, (c) => L.transferTx(c, { shop: SHOP, item: SHOE, src: DEFAULT, dst: P_B, qty: 250, opId: L.uuid(), dstMode: 'BATCHED' }));
  await L.txn(bgPool, (c) => L.transferTx(c, { shop: SHOP, item: SOCKS, src: DEFAULT, dst: P_B, qty: 120, opId: L.uuid(), dstMode: 'BATCHED' }));
  const rules = {
    A_SHOE: await L.addLimitRule(setup, { shop: SHOP, scope: 'PARTITION_ITEM', part: P_A, item: SHOE, limit: LIMITS.A_SHOE }),
    B_SHOE: await L.addLimitRule(setup, { shop: SHOP, scope: 'PARTITION_ITEM', part: P_B, item: SHOE, limit: LIMITS.B_SHOE }),
    B_TOTAL: await L.addLimitRule(setup, { shop: SHOP, scope: 'PARTITION_TOTAL', part: P_B, item: 0, limit: LIMITS.B_TOTAL }),
    SHOE_ALL: await L.addLimitRule(setup, { shop: SHOP, scope: 'ITEM_ALL_PARTITIONS', part: -1, item: SHOE, limit: LIMITS.SHOE_ALL }),
  };
  const KEYS = [key(SHOE, DEFAULT), key(SOCKS, DEFAULT), key(SHOE, P_A), key(SHOE, P_B), key(SOCKS, P_B)];
  for (const k of KEYS) await L.txn(bgPool, (c) => L.refillTx(c, k, 1));

  const facade = new L.Facade(reservePool, { soldOutTtlMs: 300 });
  let running = true;
  const pending = new Set();
  const track = (p) => { pending.add(p); p.finally(() => pending.delete(p)); };
  const loops = [];

  // expiry sweeper
  loops.push((async () => {
    while (running || pending.size) {
      const [ids] = await bgPool.query("SELECT shop_id, reservation_id FROM reservations WHERE status='ACTIVE' AND claim_deadline_at <= UTC_TIMESTAMP(6) ORDER BY claim_deadline_at LIMIT 200");
      for (const r of ids) {
        try { inc('expiry_' + (await L.txn(bgPool, (c) => L.releaseTx(c, SHOP, r.reservation_id, 'EXPIRED')))); } catch (e) { inc('expiry_err_' + (e.code || L.classify(e))); }
      }
      await L.sleep(200);
    }
  })());
  // settler + hot-key refiller for every dimension
  loops.push((async () => {
    while (running || pending.size) {
      for (const k of KEYS) {
        try { const n = await L.txn(bgPool, (c) => L.settleTx(c, k)); if (n) inc('settled_entries', n); } catch (e) { inc('settle_err_' + (e.code || L.classify(e))); }
      }
      await L.sleep(100);
    }
  })());
  loops.push((async () => {
    while (running) {
      for (const k of KEYS) {
        const [[{ p }]] = await bgPool.query('SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?', [k.shop, k.item, k.part]);
        if (Number(p) < 500) { try { inc('hotkey_refill_' + (await L.txn(bgPool, (c) => L.refillTx(c, k, 1))).outcome); } catch (e) { inc('hotkey_err_' + (e.code || L.classify(e))); } }
      }
      await L.sleep(100);
    }
  })());

  // admin events during the sale: top-up transfer into B, then close A and return its remainder to the default partition
  let returned = null;
  loops.push((async () => {
    await L.sleep(600);
    try { await L.txn(bgPool, (c) => L.transferTx(c, { shop: SHOP, item: SHOE, src: DEFAULT, dst: P_B, qty: 50, opId: L.uuid(), dstMode: 'BATCHED' })); inc('admin_topup_ok'); }
    catch (e) { inc('admin_topup_err_' + (e.code || L.classify(e))); }
    await L.sleep(400); // close A at ~1s, while it still has stock and requests keep arriving
    inc('admin_close_' + (await L.txn(bgPool, (c) => L.closePartitionTx(c, SHOP, P_A))));
    const opId = L.uuid();
    while (!returned) {
      try { returned = await L.txn(bgPool, (c) => L.returnPartition(c, { shop: SHOP, item: SHOE, part: P_A, dst: DEFAULT, opId })); inc('admin_return_ok'); }
      catch (e) { inc('admin_return_retry_' + (e.code || L.classify(e))); await L.sleep(300); }
    }
  })());

  // ---- request mix ----
  const reqs = [];
  for (let i = 0; i < REQ; i++) {
    const buyer = 'b' + Math.floor(Math.random() * BUYERS);
    const r = Math.random();
    let lines;
    if (r < 0.35) lines = [{ item: SHOE, part: P_A, qty: Math.random() < 0.9 ? 1 : 2 }];
    else if (r < 0.65) lines = [{ item: SHOE, part: P_B, qty: Math.random() < 0.7 ? 1 : 2 }];
    else if (r < 0.75) lines = [{ item: SOCKS, part: P_B, qty: 1 }];
    else if (r < 0.85) lines = [{ item: SHOE, part: P_B, qty: 1 }, { item: SOCKS, part: P_B, qty: 1 }];
    else lines = [{ item: SHOE, part: DEFAULT, qty: 1 }];
    lines.sort(L.cmpKey);
    const hash = crypto.createHash('sha256').update(JSON.stringify({ shop: SHOP, buyer, lines, ttl: TTL })).digest();
    reqs.push({ shop: SHOP, idem: 'k' + i, hash, ttl: TTL, grace: GRACE, lines, buyer });
    if (Math.random() < 0.05) reqs.push({ ...reqs[reqs.length - 1] });
  }

  let next = 0;
  const t0 = Date.now();
  async function worker() {
    while (next < reqs.length) {
      const req = reqs[next++];
      try {
        const { rid, replay } = await facade.reserve(req);
        if (replay) { inc('reserve_replay'); continue; }
        inc('reserve_ok');
        const r = Math.random();
        if (r < 0.6) {
          track((async () => {
            await L.sleep(Math.random() * 300);
            try { inc('claim_' + (await L.txn(criticalPool, (c) => L.claimTx(c, SHOP, rid, 'pay-' + req.idem)))); } catch (e) { inc('claim_err_' + (e.code || L.classify(e))); }
          })());
        } else if (r < 0.75) {
          track((async () => {
            await L.sleep(Math.random() * 300);
            try { inc('cancel_' + (await L.txn(criticalPool, (c) => L.releaseTx(c, SHOP, rid, 'CANCELLED')))); } catch (e) { inc('cancel_err_' + (e.code || L.classify(e))); }
          })());
        } else if (r < 0.85) {
          track((async () => {
            await L.sleep((TTL + GRACE + 2.5) * 1000);
            try { inc('lateclaim_' + (await L.txn(criticalPool, (c) => L.claimTx(c, SHOP, rid, 'late-' + req.idem, true)))); } catch (e) { inc('lateclaim_err_' + (e.code || L.classify(e))); }
          })());
        }
      } catch (e) {
        inc('reserve_err_' + (e.code || L.classify(e)));
        if (!e.code) console.error(e);
      }
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const reqSec = (Date.now() - t0) / 1000;
  while (pending.size) await L.sleep(100);
  for (;;) {
    const [[{ n }]] = await setup.query("SELECT COUNT(*) n FROM reservations WHERE status='ACTIVE'");
    if (Number(n) === 0 && returned) break;
    await L.sleep(300);
  }
  running = false;
  await Promise.all(loops);
  for (const k of KEYS) await L.txn(bgPool, (c) => L.settleTx(c, k));

  // ---- verification ----
  const audits = {};
  for (const k of KEYS) audits[`${k.item}@${k.part}`] = await L.audit(setup, k);
  const skuAudits = { [SHOE]: await L.auditSku(setup, SHOP, SHOE), [SOCKS]: await L.auditSku(setup, SHOP, SOCKS) };
  const held = (where, params) => setup.query(
    `SELECT r.buyer_id, SUM(q.quantity) s FROM reserved_quantities q JOIN reservations r USING (shop_id, reservation_id)
      WHERE r.status IN ('CLAIMED','ACTIVE') AND ${where} GROUP BY r.buyer_id HAVING s > ?`, params).then(([rows]) => rows);
  const over = {
    A_SHOE: await held('q.stock_partition_id=? AND q.inventory_item_id=?', [P_A, SHOE, LIMITS.A_SHOE]),
    B_SHOE: await held('q.stock_partition_id=? AND q.inventory_item_id=?', [P_B, SHOE, LIMITS.B_SHOE]),
    B_TOTAL: await held('q.stock_partition_id=?', [P_B, LIMITS.B_TOTAL]),
    SHOE_ALL: await held('q.inventory_item_id=?', [SHOE, LIMITS.SHOE_ALL]),
  };
  const mism = await L.quotaMismatches(setup);
  const [[pA]] = await setup.query('SELECT status FROM stock_partitions WHERE shop_id=? AND stock_partition_id=?', [SHOP, P_A]);
  const aAfter = audits[`${SHOE}@${P_A}`];
  const [[pend]] = await setup.query('SELECT COUNT(*) n FROM ledger_pending_entries');
  const [[dl1]] = await setup.query("SELECT COUNT c FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'");
  const [[byPart]] = await setup.query(
    `SELECT SUM(q.stock_partition_id=?) a, SUM(q.stock_partition_id=?) b, SUM(q.stock_partition_id=?) d FROM reserved_quantities q JOIN reservations r USING (shop_id, reservation_id) WHERE r.status='CLAIMED'`, [P_A, P_B, DEFAULT]);

  const expected = /^(reserve_ok|reserve_replay|reserve_err_(INSUFFICIENT_STOCK|BUYER_LIMIT_EXCEEDED|INVENTORY_BUSY|STOCK_PARTITION_CLOSED)|claim_(CLAIMED|REPLAY|CLAIMED_LATE)|claim_err_RESERVATION_EXPIRED|cancel_(CANCELLED|NOOP_\w+)|expiry_(EXPIRED|NOOP_\w+|NOT_DUE)|lateclaim_(CLAIMED_LATE_REACQUIRED|CLAIMED_LATE)|lateclaim_err_(LATE_CLAIM_INSUFFICIENT_STOCK|BUYER_LIMIT_EXCEEDED|INVENTORY_BUSY)|hotkey_refill_\w+|settled_entries|admin_topup_ok|admin_close_CLOSED|admin_return_ok|admin_return_retry_(ACTIVE_RESERVATIONS_REMAIN|INVENTORY_BUSY|STOCK_ALLOCATED))$/;
  const unexpected = Object.keys(tally).filter((k) => !expected.test(k));

  const checks = [
    ['每个维度：H>=A>=0、A=P+R、P<=C、操作记录合计−已确认=H', Object.values(audits).every((a) => a.valid),
      Object.entries(audits).map(([k, a]) => `${k}: ${a.valid ? 'ok' : a.violations.join(';')} (H=${a.H} P=${a.p} 已确认=${a.claimed})`).join(' | ')],
    ['每个 SKU 跨分区：Σ(H)=初始−已确认，划拨两侧相互抵消', Object.values(skuAudits).every((a) => a.valid),
      Object.entries(skuAudits).map(([k, a]) => `SKU${k}: ΣH=${a.total} 期望=${a.expected} 已确认=${a.claimed}`).join(' | ')],
    ['不超卖：每个 SKU 已确认 <= 初始库存', skuAudits[SHOE].claimed <= INITIAL[SHOE] && skuAudits[SOCKS].claimed <= INITIAL[SOCKS],
      `鞋 ${skuAudits[SHOE].claimed}/${INITIAL[SHOE]}，袜 ${skuAudits[SOCKS].claimed}/${INITIAL[SOCKS]}`],
    ['限购（分区A的鞋≤1、分区B的鞋≤2、分区B合计≤2、鞋跨全部分区≤3）', Object.values(over).every((x) => x.length === 0),
      Object.entries(over).map(([k, x]) => `${k} 超限 ${x.length} 人`).join('，')],
    ['买家额度与预留占用明细逐条一致', mism.length === 0, `不一致 ${mism.length}`],
    ['分区 A 已关闭、余量已退回（H=0、池为空、无 ACTIVE）', pA.status === 'CLOSED' && aAfter.H === 0 && aAfter.p === 0 && aAfter.ract === 0,
      `状态=${pA.status} H=${aAfter.H} P=${aAfter.p} R=${aAfter.ract} 退回=${returned && returned.qty}（回收池单位 ${returned && returned.reclaimed}）`],
    ['结算后无残留分录', Number(pend.n) === 0, `残留 ${pend.n}`],
    ['只出现预期内的结果码', unexpected.length === 0, unexpected.join(',') || '无'],
    ['无死锁（InnoDB lock_deadlocks 计数不变）', Number(dl1.c) === Number(dl0.c), `前 ${dl0.c} 后 ${dl1.c}`],
  ];
  const summary = {
    requests: reqs.length, concurrency: CONCURRENCY, requestPhaseSec: reqSec.toFixed(2),
    claimedLinesByPartition: { A: Number(byPart.a), B: Number(byPart.b), default: Number(byPart.d) },
    rules, facade: facade.stats, tally,
    checks: checks.map(([n, p, d]) => ({ name: n, pass: p, detail: d })),
  };
  console.log(JSON.stringify(summary, null, 2));
  require('fs').mkdirSync(`${__dirname}/results`, { recursive: true });
  require('fs').writeFileSync(`${__dirname}/results/partitions.json`, JSON.stringify(summary, null, 2));
  for (const p of [reservePool, criticalPool, bgPool]) await p.end();
  await setup.end();
  process.exit(checks.every((c) => c[1]) ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
