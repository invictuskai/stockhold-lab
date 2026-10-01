// Lock-behaviour and constraint verification against real MySQL 8.4 (reproduces the article's findings).
'use strict';
const { conn, uuid, sleep } = require('./lib');

const results = [];
function record(name, pass, detail) { results.push({ name, pass, detail }); console.log(`${pass ? 'PASS' : 'FAIL'}  ${name} — ${detail}`); }

async function locksOf(obs, table) {
  const [rows] = await obs.query(
    "SELECT INDEX_NAME idx, LOCK_MODE mode, LOCK_DATA data FROM performance_schema.data_locks WHERE OBJECT_SCHEMA='stockhold' AND OBJECT_NAME=? AND LOCK_TYPE='RECORD'", [table]);
  return rows;
}
async function reset(c) {
  for (const t of require('./lib').TABLES) await c.query(`DELETE FROM ${t}`);
  await c.query('DROP TABLE IF EXISTS units_autoinc');
}
async function seedUnits(c, item, n) {
  for (let i = 0; i < n; i++) await c.query('INSERT INTO reservation_units VALUES (1,?,1,?)', [item, uuid()]);
}

(async () => {
  const obs = await conn(), a = await conn(), b = await conn();
  await reset(obs);

  // L1: composite PK -> one record lock per claimed unit
  await seedUnits(obs, 100, 10);
  await a.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await a.beginTransaction();
  await a.query('SELECT unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY unit_id LIMIT 3 FOR UPDATE SKIP LOCKED');
  let l = await locksOf(obs, 'reservation_units');
  record('L1 复合主键：领取3个单位的行锁数', l.length === 3 && l.every((x) => x.idx === 'PRIMARY'), `${l.length} 个记录锁，索引=${[...new Set(l.map((x) => x.idx))]}，模式=${[...new Set(l.map((x) => x.mode))]}`);

  // L4 (while a holds 3 locked rows): SKIP LOCKED from b returns other rows
  await b.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await b.beginTransaction();
  const [bRows] = await b.query('SELECT unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY unit_id LIMIT 10 FOR UPDATE SKIP LOCKED');
  record('L2 SKIP LOCKED 跳过他人锁定行且不等待', bRows.length === 7, `另一事务锁 3 行后，本事务拿到 ${bRows.length} 行（期望 7）`);
  await b.rollback();

  // L5: uncommitted deletes stay visible to a plain RC COUNT (refill over-counts, never under-counts)
  const [locked] = await a.query('SELECT unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY unit_id LIMIT 3 FOR UPDATE');
  await a.query('DELETE FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 AND unit_id IN (?,?,?)', locked.map((r) => r.unit_id));
  await b.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await b.beginTransaction();
  const [[c1]] = await b.query('SELECT COUNT(*) n FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1');
  // refill-style INSERT by b must not block on a's deletes under RC
  const t0 = Date.now();
  await b.query('INSERT INTO reservation_units VALUES (1,100,1,?)', [uuid()]);
  const insMs = Date.now() - t0;
  await b.rollback();
  await a.commit();
  const [[c2]] = await obs.query('SELECT COUNT(*) n FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1');
  record('L3 RC 普通 COUNT 看得到未提交删除（补充只会少补）', Number(c1.n) === 10 && Number(c2.n) === 7, `未提交删除时 COUNT=${c1.n}，提交后=${c2.n}`);
  record('L4 RC 下补充 INSERT 不被在途预留的删除阻塞', insMs < 500, `INSERT 耗时 ${insMs}ms`);

  // L6: auto-increment PK + secondary index -> two locks per unit (article's first prototype)
  await obs.query('CREATE TABLE units_autoinc (id BIGINT AUTO_INCREMENT PRIMARY KEY, shop_id BIGINT, inventory_item_id BIGINT, stock_partition_id BIGINT, KEY k_dim (shop_id, inventory_item_id, stock_partition_id))');
  for (let i = 0; i < 10; i++) await obs.query('INSERT INTO units_autoinc (shop_id, inventory_item_id, stock_partition_id) VALUES (1,100,1)');
  await a.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await a.beginTransaction();
  await a.query('SELECT id FROM units_autoinc WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY id LIMIT 3 FOR UPDATE SKIP LOCKED');
  l = await locksOf(obs, 'units_autoinc');
  record('L5 自增主键+二级索引：领取3个单位的行锁数（复现文章“每次两把锁”）', l.length === 6, `${l.length} 个记录锁，索引=${[...new Set(l.map((x) => x.idx))]}`);
  await a.rollback();

  // L7: empty pool under REPEATABLE READ takes gap/supremum locks that block refill inserts; RC does not
  await obs.query('DELETE FROM reservation_units');
  await seedUnits(obs, 200, 3); // a different key exists after the empty range
  await seedUnits(obs, 50, 3);  // and before it
  for (const iso of ['REPEATABLE READ', 'READ COMMITTED']) {
    await a.query(`SET TRANSACTION ISOLATION LEVEL ${iso}`); await a.beginTransaction();
    await a.query('SELECT unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY unit_id LIMIT 1 FOR UPDATE SKIP LOCKED');
    const gl = await locksOf(obs, 'reservation_units');
    let blocked = false;
    try { await b.query('INSERT INTO reservation_units VALUES (1,100,1,?)', [uuid()]); } catch (e) { blocked = e.errno === 1205; }
    await a.rollback();
    await obs.query('DELETE FROM reservation_units WHERE inventory_item_id=100');
    const expectBlocked = iso === 'REPEATABLE READ';
    record(`L6 空池 ${iso}：补充 INSERT ${expectBlocked ? '被间隙锁阻塞' : '不被阻塞'}`, blocked === expectBlocked,
      `空范围锁定读后持有 ${gl.length} 个记录/间隙锁（${gl.map((x) => x.mode).join(',') || '无'}），INSERT ${blocked ? '锁等待超时' : '成功'}`);
  }

  // L8: UNION ALL with per-branch FOR UPDATE SKIP LOCKED (article gist)
  await obs.query('DELETE FROM reservation_units');
  await seedUnits(obs, 100, 5); await seedUnits(obs, 200, 5);
  await a.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await a.beginTransaction();
  const [aLocked] = await a.query('SELECT unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY unit_id LIMIT 2 FOR UPDATE');
  await b.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await b.beginTransaction();
  const [u] = await b.query(
    `(SELECT inventory_item_id, unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=100 AND stock_partition_id=1 ORDER BY unit_id LIMIT 2 FOR UPDATE SKIP LOCKED)
     UNION ALL
     (SELECT inventory_item_id, unit_id FROM reservation_units WHERE shop_id=1 AND inventory_item_id=200 AND stock_partition_id=1 ORDER BY unit_id LIMIT 3 FOR UPDATE SKIP LOCKED)`);
  const overlap = u.some((r) => aLocked.some((x) => Buffer.compare(x.unit_id, r.unit_id) === 0));
  const all = await locksOf(obs, 'reservation_units');
  record('L7 UNION ALL 分支各自 FOR UPDATE SKIP LOCKED', u.length === 5 && !overlap && all.length === 7,
    `返回 ${u.length} 行（item100=${u.filter((r) => Number(r.inventory_item_id) === 100).length}, item200=${u.filter((r) => Number(r.inventory_item_id) === 200).length}），与他人锁定行重叠=${overlap}，总记录锁=${all.length}`);
  await b.rollback(); await a.rollback();

  // C*: CHECK constraints (positive and negative)
  await reset(obs);
  const expectFail = async (name, sql, params) => {
    try { await obs.query(sql, params); record(name, false, '未被拒绝'); } catch (e) { record(name, e.errno === 3819, `被拒绝 errno=${e.errno}`); }
  };
  const expectOk = async (name, sql, params) => {
    try { await obs.query(sql, params); record(name, true, '接受'); } catch (e) { record(name, false, e.message); }
  };
  await expectFail('C1 账本 A>H 被拒绝', 'INSERT INTO inventory_ledger (shop_id,inventory_item_id,stock_partition_id,on_hand_quantity,allocated_quantity) VALUES (1,1,1,5,6)');
  await expectFail('C2 冻结状态缺原因被拒绝', "INSERT INTO inventory_ledger (shop_id,inventory_item_id,stock_partition_id,on_hand_quantity,status) VALUES (1,1,1,5,'FROZEN')");
  const ins = (status, extra) =>
    `INSERT INTO reservations (shop_id,reservation_id,idempotency_key,request_hash,status,ttl_seconds,total_quantity,line_count,expires_at,claim_deadline_at${extra.cols}) VALUES (1,?,?,?, '${status}', 60,1,1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL ${extra.grace} SECOND${extra.vals})`;
  const p = () => [uuid(), 'k' + Math.random(), Buffer.alloc(32)];
  await expectFail('C3 claim_deadline_at 超出 expires_at+120s 被拒绝', ins('ACTIVE', { grace: 121, cols: '', vals: '' }), p());
  await expectOk('C4 宽限 120s 边界接受', ins('ACTIVE', { grace: 120, cols: '', vals: '' }), p());
  await expectFail('C5 CLAIMED 缺 claim_mode 被拒绝', ins('CLAIMED', { grace: 30, cols: ',payment_reference,claimed_at', vals: ",'pay1',UTC_TIMESTAMP(6)" }), p());
  await expectOk('C6 迟到确认 CLAIMED(LATE) 保留 expired_at 接受', ins('CLAIMED', { grace: 30, cols: ',payment_reference,claimed_at,claim_mode,expired_at', vals: ",'pay2',UTC_TIMESTAMP(6),'LATE',UTC_TIMESTAMP(6)" }), p());
  await expectFail('C7 CLAIMED(RESERVED) 带 expired_at 被拒绝', ins('CLAIMED', { grace: 30, cols: ',payment_reference,claimed_at,claim_mode,expired_at', vals: ",'pay3',UTC_TIMESTAMP(6),'RESERVED',UTC_TIMESTAMP(6)" }), p());
  await expectFail('C8 CLAIM 分录 dH≠dA 被拒绝', "INSERT INTO ledger_pending_entries (shop_id,inventory_item_id,stock_partition_id,reservation_id,entry_type,on_hand_delta,allocated_delta) VALUES (1,1,1,?,'CLAIM',0,-1)", [uuid()]);
  await expectFail('C9 RELEASE 分录 dH≠0 被拒绝', "INSERT INTO ledger_pending_entries (shop_id,inventory_item_id,stock_partition_id,reservation_id,entry_type,on_hand_delta,allocated_delta) VALUES (1,1,1,?,'RELEASE',-1,-1)", [uuid()]);
  await expectFail('C10 买家额度为负被拒绝', "INSERT INTO buyer_quotas (shop_id,rule_id,buyer_id,active_quantity) VALUES (1,1,'b',-1)");
  await expectFail('C12 默认分区 0 不能关闭', "INSERT INTO stock_partitions (shop_id,stock_partition_id,status,closed_at) VALUES (1,0,'CLOSED',UTC_TIMESTAMP(6))");
  await expectOk('C13 非默认分区可以关闭', "INSERT INTO stock_partitions (shop_id,stock_partition_id,status,closed_at) VALUES (1,7,'CLOSED',UTC_TIMESTAMP(6))");
  await expectFail('C14 SKU 跨分区规则必须用分区哨兵 -1', "INSERT INTO purchase_limit_rules (shop_id,scope_type,stock_partition_id,inventory_item_id,per_buyer_limit) VALUES (1,'ITEM_ALL_PARTITIONS',0,100,1)");
  await expectFail('C15 分区合计规则的 SKU 必须为 0', "INSERT INTO purchase_limit_rules (shop_id,scope_type,stock_partition_id,inventory_item_id,per_buyer_limit) VALUES (1,'PARTITION_TOTAL',7,100,1)");
  await expectOk('C16 三种范围的合法规则', "INSERT INTO purchase_limit_rules (shop_id,scope_type,stock_partition_id,inventory_item_id,per_buyer_limit) VALUES (1,'PARTITION_ITEM',7,100,1),(1,'PARTITION_TOTAL',7,0,2),(1,'ITEM_ALL_PARTITIONS',-1,100,3)");
  try { await obs.query("INSERT INTO purchase_limit_rules (shop_id,scope_type,stock_partition_id,inventory_item_id,per_buyer_limit) VALUES (1,'PARTITION_ITEM',7,100,5)"); record('C17 同一范围不能重复建规则', false, '未被拒绝'); }
  catch (e) { record('C17 同一范围不能重复建规则', e.errno === 1062, `被拒绝 errno=${e.errno}`); }
  await expectFail('C18 划入行的 delta 必须为正', "INSERT INTO inventory_operations (shop_id,operation_id,line_no,request_hash,operation_type,inventory_item_id,stock_partition_id,delta_quantity,on_hand_after,allocated_after,pool_capacity_after) VALUES (1,?,2,?,'TRANSFER_IN',1,7,-5,0,0,1)", [uuid(), Buffer.alloc(32)]);
  await expectFail('C19 划出行必须是 line_no=1', "INSERT INTO inventory_operations (shop_id,operation_id,line_no,request_hash,operation_type,inventory_item_id,stock_partition_id,delta_quantity,on_hand_after,allocated_after,pool_capacity_after) VALUES (1,?,2,?,'TRANSFER_OUT',1,0,-5,0,0,1)", [uuid(), Buffer.alloc(32)]);
  await expectFail('C11 回收数量用于正向调整被拒绝', "INSERT INTO inventory_operations (shop_id,operation_id,request_hash,operation_type,inventory_item_id,stock_partition_id,delta_quantity,on_hand_after,allocated_after,pool_capacity_after,pool_reclaimed_quantity) VALUES (1,?,?,'ADJUST',1,1,5,10,0,1000,3)", [uuid(), Buffer.alloc(32)]);

  // Q1: buyer quota ODKU — same buyer concurrent first insert serializes, no deadlock
  await a.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await a.beginTransaction();
  await a.query("INSERT INTO buyer_quotas (shop_id,rule_id,buyer_id,active_quantity) VALUES (1,9,'u1',1) ON DUPLICATE KEY UPDATE active_quantity=active_quantity+1");
  await b.query('SET SESSION innodb_lock_wait_timeout=5');
  await b.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED'); await b.beginTransaction();
  const pB = b.query("INSERT INTO buyer_quotas (shop_id,rule_id,buyer_id,active_quantity) VALUES (1,9,'u1',1) ON DUPLICATE KEY UPDATE active_quantity=active_quantity+1");
  await sleep(300);
  await a.commit();
  await pB; await b.commit();
  const [[q]] = await obs.query("SELECT active_quantity v FROM buyer_quotas WHERE buyer_id='u1'");
  record('Q1 同一买家并发首次下单在额度行上串行化', Number(q.v) === 2, `最终 active_quantity=${q.v}（期望 2）`);

  await reset(obs);
  for (const c of [obs, a, b]) await c.end();
  const failed = results.filter((r) => !r.pass);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  require('fs').mkdirSync(__dirname + '/results', { recursive: true });
  require('fs').writeFileSync(__dirname + '/results/locks-result.json', JSON.stringify(results, null, 2));
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
