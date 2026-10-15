#!/bin/sh
# 由 compose 在首次初始化数据目录时执行（/docker-entrypoint-initdb.d）。已有数据目录不会重跑。
# 1. 创建可随时重置的测试库 stockhold_test，业务账号拥有其全部权限；
# 2. 设置了 STOCKHOLD_TEST_OBSERVER_PASSWORD 时，创建只读 performance_schema 的观察账号，
#    供锁形态测试读取 data_locks。业务账号本身不授予 performance_schema 权限。
set -eu

mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS stockhold_test;
GRANT ALL PRIVILEGES ON stockhold_test.* TO '$MYSQL_USER'@'%';
SQL

if [ -n "${STOCKHOLD_TEST_OBSERVER_PASSWORD:-}" ]; then
    mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS '${STOCKHOLD_TEST_OBSERVER_USER:-stockhold_observer}'@'%'
    IDENTIFIED BY '$STOCKHOLD_TEST_OBSERVER_PASSWORD';
GRANT SELECT ON performance_schema.* TO '${STOCKHOLD_TEST_OBSERVER_USER:-stockhold_observer}'@'%';
SQL
fi
