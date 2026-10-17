#!/usr/bin/env bash
# 初始化只读副本（compose 的 replica profile）：主库创建复制账号；副本清空 GTID 后装载主库带 GTID 的一致性备份；
# 然后按 GTID 自动定位开始复制，最后开启 super_read_only。可重复执行（会重新装载副本）。
# 用法：docker compose --profile replica up -d && scripts/replica-setup.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if command -v docker-compose >/dev/null 2>&1; then compose=(docker-compose); else compose=(docker compose); fi
primary() { "${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'; }
replica() { "${compose[@]}" exec -T mysql-replica sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'; }

repl_password=$(openssl rand -hex 16)
primary <<SQL
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY '${repl_password}';
ALTER USER 'repl'@'%' IDENTIFIED BY '${repl_password}';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
SQL

replica <<'SQL'
STOP REPLICA;
RESET REPLICA ALL;
SET GLOBAL super_read_only = OFF;
SET GLOBAL read_only = OFF;
RESET BINARY LOGS AND GTIDS;
SQL

# 先落到临时文件再装载：直接把一个 compose exec 管道接到另一个上，实测会静默失败。
dump=$(mktemp -t stockhold-replica-dump.XXXXXX)
trap 'rm -f "$dump"' EXIT
echo "dumping the primary (consistent snapshot with GTID_PURGED)..."
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --all-databases \
  --single-transaction --set-gtid-purged=ON --routines --events --triggers' > "$dump"
echo "loading $(wc -c < "$dump" | tr -d ' ') bytes into the replica..."
replica < "$dump" || { echo "loading the dump into the replica failed" >&2; exit 1; }

replica <<SQL
CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'mysql', SOURCE_PORT = 3306, SOURCE_USER = 'repl',
  SOURCE_PASSWORD = '${repl_password}', SOURCE_AUTO_POSITION = 1, GET_SOURCE_PUBLIC_KEY = 1;
START REPLICA;
SET PERSIST read_only = ON;
SET PERSIST super_read_only = ON;
SQL

# 连接线程与应用线程都为 ON 才算复制正常（-N 模式下 SHOW REPLICA STATUS\G 不输出字段名，不便解析）。
state_sql="SELECT CONCAT((SELECT SERVICE_STATE FROM performance_schema.replication_connection_status), '/',
  (SELECT SERVICE_STATE FROM performance_schema.replication_applier_status))"
for _ in $(seq 1 30); do
  state=$(replica <<<"$state_sql")
  [[ "$state" == "ON/ON" ]] && { echo "replication running (connection/applier: $state)"; exit 0; }
  sleep 1
done
echo "replication not running (connection/applier: ${state:-unknown})" >&2
"${compose[@]}" exec -T mysql-replica sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SHOW REPLICA STATUS\G"' \
  | grep -E 'Running:|Last_.*Error:' >&2 || true
exit 1
