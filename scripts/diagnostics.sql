-- 只读诊断 SQL（实现与验证计划第 7 节）。需要 performance_schema / PROCESS 权限，
-- 用独立管理连接执行，不要把这些权限授予应用业务账号，也不要占用业务连接池。
-- 把下面三个变量换成要观察的库存维度。
SET @shop_id = 1001, @item_id = 2001, @location_id = 3001;

-- 1. 单位领取查询应使用复合 PRIMARY（type=ref，Using index），不应出现全表扫描。
--    注意：只用 EXPLAIN。EXPLAIN ANALYZE 会真正执行并加锁，只能在专用实验环境使用。
EXPLAIN
SELECT unit_id FROM reservation_units
 WHERE shop_id = @shop_id AND inventory_item_id = @item_id AND location_id = @location_id
 ORDER BY unit_id LIMIT 3 FOR UPDATE SKIP LOCKED;

-- 2. 到期扫描应使用 idx_reservation_expiry。
EXPLAIN
SELECT shop_id, reservation_id, expires_at FROM reservations
 WHERE status = 'ACTIVE' AND expires_at <= UTC_TIMESTAMP(6)
 ORDER BY expires_at, shop_id, reservation_id LIMIT 200;

-- 3. 当前持有 / 等待的行锁，按表、索引、锁模式汇总。
--    期望 reservation_units 上只有 X,REC_NOT_GAP（READ COMMITTED 下没有间隙锁）。
SELECT object_name, index_name, lock_type, lock_mode, lock_status, COUNT(*) AS locks
  FROM performance_schema.data_locks
 WHERE object_schema = DATABASE()
 GROUP BY object_name, index_name, lock_type, lock_mode, lock_status
 ORDER BY locks DESC;

-- 4. 谁在等谁：阻塞关系及双方正在执行的语句（conn_tag 注释标明业务路径）。
SELECT w.requesting_engine_transaction_id AS waiting_trx,
       w.blocking_engine_transaction_id   AS blocking_trx,
       rl.object_name, rl.index_name, rl.lock_mode AS waiting_mode,
       rt.trx_query AS waiting_query, bt.trx_query AS blocking_query,
       TIMESTAMPDIFF(MICROSECOND, bt.trx_started, NOW(6)) / 1000 AS blocking_trx_age_ms
  FROM performance_schema.data_lock_waits w
  JOIN performance_schema.data_locks rl ON rl.engine_lock_id = w.requesting_engine_lock_id
  JOIN information_schema.innodb_trx rt ON rt.trx_id = w.requesting_engine_transaction_id
  JOIN information_schema.innodb_trx bt ON bt.trx_id = w.blocking_engine_transaction_id;

-- 5. 当前事务：时长、状态、持有的行锁数与有效隔离级别。长事务会直接抬高连接持有时间。
SELECT trx_id, trx_state, trx_isolation_level, trx_rows_locked, trx_rows_modified,
       TIMESTAMPDIFF(MICROSECOND, trx_started, NOW(6)) / 1000 AS age_ms,
       LEFT(trx_query, 120) AS query
  FROM information_schema.innodb_trx
 ORDER BY trx_started;

-- 6. 线程排队与连接使用。
SHOW GLOBAL STATUS WHERE Variable_name IN
    ('Threads_running', 'Threads_connected', 'Max_used_connections',
     'Innodb_row_lock_current_waits', 'Innodb_row_lock_waits', 'Innodb_row_lock_time_avg');
SHOW GLOBAL VARIABLES WHERE Variable_name IN
    ('max_connections', 'innodb_thread_concurrency', 'innodb_lock_wait_timeout', 'transaction_isolation');

-- 7. 按固定业务标签统计语句（digest 文本保留不了注释，这里按当前正在执行的语句统计）。
SELECT REGEXP_SUBSTR(info, 'conn_tag:[a-z]+') AS conn_tag, COUNT(*) AS running
  FROM performance_schema.processlist
 WHERE info LIKE '%conn_tag:%' AND id <> CONNECTION_ID()
 GROUP BY conn_tag;

-- 8. 最近一次死锁详情（LATEST DETECTED DEADLOCK 段）。
SHOW ENGINE INNODB STATUS;

-- 9. 单维度一致性快照：A = P + R，P <= pool_capacity。
SELECT l.on_hand_quantity AS H, l.allocated_quantity AS A, l.pool_capacity AS C,
       (SELECT COUNT(*) FROM reservation_units u
         WHERE u.shop_id = l.shop_id AND u.inventory_item_id = l.inventory_item_id
           AND u.location_id = l.location_id) AS P,
       (SELECT COALESCE(SUM(q.quantity), 0) FROM reserved_quantities q
          JOIN reservations r ON r.shop_id = q.shop_id AND r.reservation_id = q.reservation_id
         WHERE q.shop_id = l.shop_id AND q.inventory_item_id = l.inventory_item_id
           AND q.location_id = l.location_id AND r.status = 'ACTIVE') AS R
  FROM inventory_ledger l
 WHERE l.shop_id = @shop_id AND l.inventory_item_id = @item_id AND l.location_id = @location_id;
