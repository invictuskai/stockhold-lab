// Deterministic edge cases for logical stock partitions and scoped purchase limits.
'use strict';
const crypto = require('crypto');
const L = require('./lib');

const SHOP = 1, ITEM = 300;
const results = [];
const record = (name, pass, detail) => { results.push({ name, pass, detail }); console.log(`${pass ? 'PASS' : 'FAIL'}  ${name} — ${detail}`); };
const code = (e) => e.code || L.classify(e);
const req = (part, qty, buyer, extra = {}) => ({
  shop: SHOP, idem: 'e-' + crypto.randomUUID(), hash: crypto.randomBytes(32), ttl: 300, grace: 30,
  lines: [{ item: ITEM, part, qty }], buyer, ...extra,
});

(async () => {
  const pool = L.makePool(8);
  const c0 = await L.conn();
  const tx = (fn) => L.txn(pool, fn);
  const facade = new L.Facade(pool);
  const valid = async (part) => (await L.audit(c0, { shop: SHOP, item: ITEM, part })).valid;

  // E1 transfer that must reclaim pool units: F=0, pool full
  await L.resetAll(c0, SHOP);
  await tx((c) => L.initializeTx(c, { shop: SHOP, item: ITEM, part: 0, qty: 100, opId: L.uuid() }));
  await tx((c) => L.createPartitionTx(c, SHOP, 7));
  await tx((c) => L.refillTx(c, { shop: SHOP, item: ITEM, part: 0 }, 1));
  const r1 = await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 7, qty: 30, opId: L.uuid() }));
  const a1 = await L.audit(c0, { shop: SHOP, item: ITEM, part: 0 });
  record('E1 划出时 F=0、池已满：回收池单位后划拨', r1.reclaimed === 30 && a1.valid && a1.H === 70 && a1.p === 70 && (await valid(7)),
    `回收 ${r1.reclaimed}，源 H=${a1.H} P=${a1.p}，审计 ${a1.valid && (await valid(7))}`);

  // E2 transfer blocked because the gap is held by ACTIVE reservations
  const rids = [];
  for (let i = 0; i < 65; i++) rids.push((await facade.reserve(req(0, 1))).rid); // R=65, P=5, H=70
  try { await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 7, qty: 20, opId: L.uuid() })); record('E2 缺口被 ACTIVE 预留占用时拒绝划拨', false, '未被拒绝'); }
  catch (e) {
    const [[ops]] = await c0.query("SELECT COUNT(*) n FROM inventory_operations WHERE operation_type LIKE 'TRANSFER%'");
    record('E2 缺口被 ACTIVE 预留占用时拒绝划拨', code(e) === 'STOCK_ALLOCATED' && Number(ops.n) === 2 && (await valid(0)), `错误=${code(e)}，划拨记录仍只有 E1 的 2 行=${ops.n}，审计 ${await valid(0)}`);
  }

  // E3 idempotent transfer: replaying the same operation id moves stock only once
  const opId = L.uuid();
  await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 7, qty: 2, opId }));
  let dup = null;
  try { await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 7, qty: 2, opId })); } catch (e) { dup = e; }
  const a3 = await L.audit(c0, { shop: SHOP, item: ITEM, part: 7 });
  record('E3 划拨幂等：同一 operation_id 重放不会重复划拨', dup && dup.errno === 1062 && a3.H === 32 && a3.valid, `重放错误=${dup && dup.code}，分区 7 H=${a3.H}（期望 32）`);

  // E4 close: reserve and refill are refused, transfer into the closed partition is refused
  for (const rid of rids) await tx((c) => L.releaseTx(c, SHOP, rid, 'CANCELLED'));
  await tx((c) => L.refillTx(c, { shop: SHOP, item: ITEM, part: 7 }, 1));
  await tx((c) => L.closePartitionTx(c, SHOP, 7));
  let e4a = null, e4c = null;
  try { await facade.reserve(req(7, 1)); } catch (e) { e4a = code(e); }
  const e4b = (await tx((c) => L.refillTx(c, { shop: SHOP, item: ITEM, part: 7 }, 1))).outcome;
  try { await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 7, qty: 1, opId: L.uuid() })); } catch (e) { e4c = code(e); }
  record('E4 关闭后：拒绝预留、拒绝补充、拒绝划入', e4a === 'STOCK_PARTITION_CLOSED' && e4b === 'CLOSED' && e4c === 'STOCK_PARTITION_CLOSED', `预留=${e4a} 补充=${e4b} 划入=${e4c}`);

  // E5 return while a unit is locked by an in-flight reserve -> BUSY, then succeeds after it rolls back
  const holder = await L.conn();
  await holder.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await holder.beginTransaction();
  await holder.query('SELECT unit_id FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=7 LIMIT 1 FOR UPDATE', [SHOP, ITEM]);
  let e5 = null;
  try { await tx((c) => L.returnPartition(c, { shop: SHOP, item: ITEM, part: 7, dst: 0, opId: L.uuid() })); } catch (e) { e5 = code(e); }
  await holder.rollback(); await holder.end();
  const r5 = await tx((c) => L.returnPartition(c, { shop: SHOP, item: ITEM, part: 7, dst: 0, opId: L.uuid() }));
  const a5 = await L.audit(c0, { shop: SHOP, item: ITEM, part: 7 });
  const s5 = await L.auditSku(c0, SHOP, ITEM);
  record('E5 单位被在途预留锁住时退回返回 BUSY，释放后退回成功', e5 === 'INVENTORY_BUSY' && r5.qty === 32 && a5.H === 0 && a5.p === 0 && s5.valid,
    `第一次=${e5}，第二次退回 ${r5.qty}（回收 ${r5.reclaimed}），分区 7 H=${a5.H} P=${a5.p}，SKU 合计审计 ${s5.valid}`);

  // E6 return refused while ACTIVE reservations remain
  await tx((c) => L.createPartitionTx(c, SHOP, 8));
  await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 8, qty: 5, opId: L.uuid() }));
  const rid8 = (await facade.reserve(req(8, 1))).rid;
  await tx((c) => L.closePartitionTx(c, SHOP, 8));
  let e6 = null;
  try { await tx((c) => L.returnPartition(c, { shop: SHOP, item: ITEM, part: 8, dst: 0, opId: L.uuid() })); } catch (e) { e6 = code(e); }
  const claim8 = await tx((c) => L.claimTx(c, SHOP, rid8, 'pay-8'));
  const r6 = await tx((c) => L.returnPartition(c, { shop: SHOP, item: ITEM, part: 8, dst: 0, opId: L.uuid() }));
  record('E6 仍有 ACTIVE 预留时拒绝退回；关闭后已有预留仍可确认', e6 === 'ACTIVE_RESERVATIONS_REMAIN' && claim8 === 'CLAIMED' && r6.qty === 4 && (await valid(8)),
    `退回=${e6}，确认=${claim8}，之后退回 ${r6.qty}`);

  // E7 late claim after the partition was returned -> LATE_CLAIM_INSUFFICIENT_STOCK
  await tx((c) => L.createPartitionTx(c, SHOP, 9));
  await tx((c) => L.transferTx(c, { shop: SHOP, item: ITEM, src: 0, dst: 9, qty: 3, opId: L.uuid() }));
  const rid9 = (await facade.reserve(req(9, 1))).rid;
  await tx((c) => L.releaseTx(c, SHOP, rid9, 'CANCELLED'));
  await c0.query("UPDATE reservations SET status='EXPIRED', cancelled_at=NULL, expired_at=UTC_TIMESTAMP(6) WHERE reservation_id=?", [rid9]); // simulate expiry outcome
  await tx((c) => L.closePartitionTx(c, SHOP, 9));
  await tx((c) => L.returnPartition(c, { shop: SHOP, item: ITEM, part: 9, dst: 0, opId: L.uuid() }));
  let e7 = null;
  try { await tx((c) => L.claimTx(c, SHOP, rid9, 'late-9', true)); } catch (e) { e7 = code(e); }
  record('E7 分区退回后迟到确认：无法兑现，返回 LATE_CLAIM_INSUFFICIENT_STOCK', e7 === 'LATE_CLAIM_INSUFFICIENT_STOCK' && (await valid(9)), `结果=${e7}`);

  // E8 scoped limits: missing buyer id, limit change and disable mid-sale, rule added after a reservation
  await tx((c) => L.refillTx(c, { shop: SHOP, item: ITEM, part: 0 }, 1));
  const rule = await L.addLimitRule(c0, { shop: SHOP, scope: 'PARTITION_ITEM', part: 0, item: ITEM, limit: 1 });
  let e8a = null, e8b = null;
  try { await facade.reserve(req(0, 1)); } catch (e) { e8a = code(e); }
  const u1 = (await facade.reserve(req(0, 1, 'alice'))).rid;
  try { await facade.reserve(req(0, 1, 'alice')); } catch (e) { e8b = code(e); }
  await c0.query('UPDATE purchase_limit_rules SET per_buyer_limit=3 WHERE rule_id=?', [rule]);
  const u2 = (await facade.reserve(req(0, 1, 'alice'))).rid;
  await c0.query("UPDATE purchase_limit_rules SET status='DISABLED' WHERE rule_id=?", [rule]);
  const u3 = (await facade.reserve(req(0, 5, 'alice'))).rid; // no longer limited, no usage recorded
  const rule2 = await L.addLimitRule(c0, { shop: SHOP, scope: 'ITEM_ALL_PARTITIONS', part: -1, item: ITEM, limit: 1 });
  for (const rid of [u1, u3]) await tx((c) => L.releaseTx(c, SHOP, rid, 'CANCELLED'));
  await tx((c) => L.claimTx(c, SHOP, u2, 'pay-u2'));
  const mism = await L.quotaMismatches(c0);
  const [[q]] = await c0.query('SELECT active_quantity a, claimed_quantity c FROM buyer_quotas WHERE rule_id=? AND buyer_id=?', [rule, 'alice']);
  const [[q2]] = await c0.query('SELECT COUNT(*) n FROM buyer_quotas WHERE rule_id=?', [rule2]);
  record('E8 限购：缺 buyerId 拒绝；超限拒绝；中途改值 / 停用 / 新增规则后，额度按占用明细原路退回',
    e8a === 'BUYER_ID_REQUIRED' && e8b === 'BUYER_LIMIT_EXCEEDED' && mism.length === 0 && Number(q.a) === 0 && Number(q.c) === 1 && Number(q2.n) === 0,
    `缺buyer=${e8a} 超限=${e8b}；alice 额度 active=${q.a} claimed=${q.c}；事后新增规则未被误扣（额度行 ${q2.n}）；不一致 ${mism.length}`);

  const all = [0, 7, 8, 9];
  const audits = await Promise.all(all.map((p) => L.audit(c0, { shop: SHOP, item: ITEM, part: p })));
  const sku = await L.auditSku(c0, SHOP, ITEM);
  record('E9 全部维度与 SKU 合计审计', audits.every((a) => a.valid) && sku.valid, `维度 ${audits.map((a) => a.valid).join(',')}，SKU ΣH=${sku.total} 期望=${sku.expected}`);

  await pool.end(); await c0.end();
  const failed = results.filter((r) => !r.pass);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  require('fs').mkdirSync(__dirname + '/results', { recursive: true });
  require('fs').writeFileSync(__dirname + '/results/partition-edges.json', JSON.stringify(results, null, 2));
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
