// End-to-end flash-sale simulation against MySQL 8.4: correctness under contention for SYNC / BATCHED settlement.
'use strict';
const crypto = require('crypto');
const L = require('./lib');

const MODE = process.argv[2] || 'BATCHED';
const STOCK = Number(process.env.STOCK || 3000);
const CAP = 1000;
const REQ = Number(process.env.REQ || 30000);
const BUYERS = Number(process.env.BUYERS || 12000);
const LIMIT = 2;
const CONCURRENCY = Number(process.env.CONC || 64);
const TTL = 5, GRACE = 1;
const K = { shop: 1, item: 100, loc: 1 };

const tally = {};
const inc = (k) => (tally[k] = (tally[k] || 0) + 1);

(async () => {
  const reservePool = L.makePool(32), criticalPool = L.makePool(16), bgPool = L.makePool(4);
  const setup = await L.conn();
  for (const t of ['reservation_units', 'inventory_ledger', 'reservations', 'reserved_quantities', 'ledger_pending_entries', 'buyer_quotas', 'item_purchase_limits'])
    await setup.query(`DELETE FROM ${t}`);
  await setup.query('INSERT INTO inventory_ledger (shop_id,inventory_item_id,location_id,on_hand_quantity,allocated_quantity,pool_capacity,settlement_mode) VALUES (?,?,?,?,0,?,?)',
    [K.shop, K.item, K.loc, STOCK, CAP, MODE]);
  await setup.query('INSERT INTO item_purchase_limits (shop_id,inventory_item_id,per_buyer_limit) VALUES (?,?,?)', [K.shop, K.item, LIMIT]);
  // pre-warm (秒杀前预热): fill the pool before the sale starts
  await L.txn(bgPool, (c) => L.refillTx(c, K, 1));

  const facade = new L.Facade(reservePool, { soldOutTtlMs: 300 });
  let running = true;
  const pendingActions = new Set();
  const track = (p) => { pendingActions.add(p); p.finally(() => pendingActions.delete(p)); };

  // background: expiry sweeper, settler, hot-key refiller (background bulkhead)
  const loops = [];
  loops.push((async () => {
    while (running || pendingActions.size) {
      const [ids] = await bgPool.query("SELECT reservation_id FROM reservations WHERE status='ACTIVE' AND claim_deadline_at <= UTC_TIMESTAMP(6) ORDER BY claim_deadline_at LIMIT 200");
      for (const r of ids) {
        try { inc('expiry_' + (await L.txn(bgPool, (c) => L.releaseTx(c, K.shop, r.reservation_id, 'EXPIRED')))); } catch (e) { inc('expiry_err_' + L.classify(e)); }
      }
      await L.sleep(200);
    }
  })());
  // SWITCH=1: flip settlement mode every 300ms during the sale (both paths must keep the invariants)
  if (process.env.SWITCH === '1') loops.push((async () => {
    let m = MODE;
    while (running) {
      await L.sleep(300);
      m = m === 'SYNC' ? 'BATCHED' : 'SYNC';
      await bgPool.query('UPDATE inventory_ledger SET settlement_mode=? WHERE shop_id=? AND inventory_item_id=? AND location_id=?', [m, K.shop, K.item, K.loc]);
      inc('mode_switches');
    }
  })());
  // SETTLER=0: no settlement job; only inline settlement inside refill / late claim
  if (process.env.SETTLER !== '0') loops.push((async () => {
    while (running || pendingActions.size) {
      try { const n = await L.txn(bgPool, (c) => L.settleTx(c, K)); if (n) { inc('settle_tx_nonempty'); tally.settled_entries = (tally.settled_entries || 0) + n; } } catch (e) { inc('settle_err_' + L.classify(e)); }
      await L.sleep(100);
    }
  })());
  loops.push((async () => {
    while (running) {
      const [[{ p }]] = await bgPool.query('SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=?', [K.shop, K.item, K.loc]);
      if (Number(p) < CAP / 2) { try { const r = await L.txn(bgPool, (c) => L.refillTx(c, K, 1)); inc('hotkey_refill_' + r.outcome); } catch (e) { inc('hotkey_err_' + L.classify(e)); } }
      await L.sleep(100);
    }
  })());

  // request generator
  const reqs = [];
  for (let i = 0; i < REQ; i++) {
    const buyer = 'b' + Math.floor(Math.random() * BUYERS);
    const r = Math.random();
    const qty = r < 0.8 ? 1 : r < 0.95 ? 2 : 3;
    const idem = 'k' + i;
    const lines = [{ item: K.item, loc: K.loc, qty }];
    const hash = crypto.createHash('sha256').update(JSON.stringify({ shop: K.shop, buyer, lines, ttl: TTL })).digest();
    reqs.push({ shop: K.shop, idem, hash, ttl: TTL, grace: GRACE, lines, buyer });
    if (Math.random() < 0.05) reqs.push({ ...reqs[reqs.length - 1], replayOf: idem }); // client retry with same key
  }

  const ridByIdem = new Map();
  let successWindowEnd = 0;
  const t0 = Date.now();
  let next = 0;
  async function worker() {
    while (next < reqs.length) {
      const req = reqs[next++];
      try {
        const { rid, replay } = await facade.reserve(req);
        if (replay) {
          inc('reserve_replay');
          if (ridByIdem.has(req.idem) && !Buffer.from(ridByIdem.get(req.idem)).equals(Buffer.from(rid))) inc('ERROR_replay_mismatch');
          continue;
        }
        inc('reserve_ok');
        successWindowEnd = Date.now();
        ridByIdem.set(req.idem, rid);
        const r = Math.random();
        if (r < 0.6) {
          track((async () => {
            await L.sleep(Math.random() * 300);
            const pref = 'pay-' + req.idem;
            try { inc('claim_' + (await L.txn(criticalPool, (c) => L.claimTx(c, K.shop, rid, pref)))); } catch (e) { inc('claim_err_' + (e.code || L.classify(e))); }
            if (Math.random() < 0.05) { try { inc('claim_' + (await L.txn(criticalPool, (c) => L.claimTx(c, K.shop, rid, pref)))); } catch (e) { inc('claim_err_' + (e.code || L.classify(e))); } }
          })());
        } else if (r < 0.75) {
          track((async () => {
            await L.sleep(Math.random() * 300);
            try { inc('cancel_' + (await L.txn(criticalPool, (c) => L.releaseTx(c, K.shop, rid, 'CANCELLED')))); } catch (e) { inc('cancel_err_' + L.classify(e)); }
          })());
        } else if (r < 0.85) {
          // payment succeeded only after expiry: explicit late claim (re-acquire)
          track((async () => {
            await L.sleep((TTL + GRACE + 2.5) * 1000);
            try { inc('lateclaim_' + (await L.txn(criticalPool, (c) => L.claimTx(c, K.shop, rid, 'late-' + req.idem, true)))); } catch (e) { inc('lateclaim_err_' + (e.code || L.classify(e))); }
          })());
        } // else: abandoned, will expire
      } catch (e) {
        inc('reserve_err_' + (e.code || L.classify(e)));
        if (!e.code) console.error(e);
      }
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const reqDone = Date.now();
  while (pendingActions.size) await L.sleep(100);
  // let expiry drain everything still ACTIVE
  for (;;) {
    const [[{ n }]] = await setup.query("SELECT COUNT(*) n FROM reservations WHERE status='ACTIVE'");
    if (Number(n) === 0) break;
    await L.sleep(300);
  }
  running = false;
  await Promise.all(loops);
  await L.txn(bgPool, (c) => L.settleTx(c, K));

  // ---- verification ----
  const a = await L.audit(setup, K, STOCK);
  const [[cl]] = await setup.query(
    "SELECT COALESCE(SUM(q.quantity),0) s FROM reserved_quantities q JOIN reservations r USING (shop_id, reservation_id) WHERE r.status='CLAIMED'");
  const [over] = await setup.query(
    "SELECT r.buyer_id, SUM(q.quantity) s FROM reserved_quantities q JOIN reservations r USING (shop_id, reservation_id) WHERE r.status IN ('CLAIMED','ACTIVE') GROUP BY r.buyer_id HAVING s > ?", [LIMIT]);
  const [quotaMismatch] = await setup.query(
    `SELECT b.buyer_id, b.active_quantity, b.claimed_quantity, COALESCE(x.s,0) actual FROM buyer_quotas b LEFT JOIN
       (SELECT r.buyer_id, SUM(q.quantity) s FROM reserved_quantities q JOIN reservations r USING (shop_id, reservation_id) WHERE r.status='CLAIMED' GROUP BY r.buyer_id) x
       ON x.buyer_id=b.buyer_id WHERE b.active_quantity <> 0 OR b.claimed_quantity <> COALESCE(x.s,0)`);
  const [[pend]] = await setup.query('SELECT COUNT(*) n FROM ledger_pending_entries');
  const expected = /^(reserve_ok|reserve_replay|reserve_err_(INSUFFICIENT_STOCK|BUYER_LIMIT_EXCEEDED|INVENTORY_BUSY)|claim_(CLAIMED|REPLAY)|cancel_(CANCELLED|NOOP_\w+)|expiry_(EXPIRED|NOOP_\w+|NOT_DUE)|lateclaim_CLAIMED_LATE_REACQUIRED|lateclaim_CLAIMED_LATE|lateclaim_err_(LATE_CLAIM_INSUFFICIENT_STOCK|BUYER_LIMIT_EXCEEDED|INVENTORY_BUSY)|claim_err_RESERVATION_EXPIRED|hotkey_refill_\w+|settle_tx_nonempty|settled_entries|mode_switches)$/;
  const unexpected = Object.keys(tally).filter((k) => !expected.test(k));

  const checks = [
    ['不变量 H>=A>=0、A=P+R、P<=C、初始-已确认=H', a.valid, a.violations.join('; ') || `H=${a.H} A=${a.A} P=${a.p} R=${a.ract} 已确认=${a.claimed}`],
    ['不超卖：已确认 <= 初始库存', Number(cl.s) <= STOCK, `已确认 ${cl.s} / 库存 ${STOCK}`],
    ['限购：任何买家 ACTIVE+已确认 <= 2', over.length === 0, `超限买家 ${over.length}`],
    ['买家额度表与实际确认量一致、无残留 ACTIVE', quotaMismatch.length === 0, `不一致 ${quotaMismatch.length}`],
    ['结算后无残留分录', Number(pend.n) === 0, `残留 ${pend.n}`],
    ['只出现预期内的结果（无不变量异常、数据库错误、幂等重放不一致）', unexpected.length === 0, unexpected.join(',') || '无'],
  ];
  const summary = {
    mode: MODE, stock: STOCK, requests: reqs.length, concurrency: CONCURRENCY,
    reserveWindowSec: ((successWindowEnd - t0) / 1000).toFixed(2), allRequestsSec: ((reqDone - t0) / 1000).toFixed(2),
    reserveOkPerSec: Math.round((tally.reserve_ok || 0) / ((successWindowEnd - t0) / 1000)),
    facade: facade.stats, tally, audit: a,
    checks: checks.map(([n, p, d]) => ({ name: n, pass: p, detail: d })),
  };
  console.log(JSON.stringify(summary, null, 2));
  const variant = (process.env.SWITCH === '1' ? '-switch' : '') + (process.env.SETTLER === '0' ? '-nosettler' : '');
  summary.variant = variant || 'default';
  require('fs').mkdirSync(`${__dirname}/results`, { recursive: true });
  require('fs').writeFileSync(`${__dirname}/results/flashsale-${MODE}${variant}.json`, JSON.stringify(summary, null, 2));
  for (const p of [reservePool, criticalPool, bgPool]) await p.end();
  await setup.end();
  process.exit(checks.every((c) => c[1]) ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
