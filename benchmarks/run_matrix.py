#!/usr/bin/env python3
"""压测矩阵执行器：启停应用、清库、播种、预热、测量、采样与正确性审计，结果写成 JSON。

前置条件：compose 中的 MySQL 已启动（docker compose up -d）、.env 已填写、
./mvnw -DskipTests package 已生成 jar、本机安装 k6。只用 Python 标准库。

    python3 benchmarks/run_matrix.py --out benchmarks/reports/raw/2026-10-15
    python3 benchmarks/run_matrix.py --out /tmp/try --only E1 --reps 1 --duration 20s

每组实验使用一个新启动的应用进程；每次运行前清空库存相关表并重新播种，
先做不计入结果的预热，再做固定并发的测量窗口。指标取测量窗口前后的差值。
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / 'target' / 'stockhold-lab-0.1.0-SNAPSHOT.jar'
BASE_PORT = 8080
PORTS = [BASE_PORT]  # 当前组启动的应用实例端口；指标在所有实例上求和
SHOP_ID = 1001
FIRST_ITEM_ID = 2001
LOCATION_ID = 3001
# 账本必须最先清空：应用的后台补充任务一直在运行，若先清单位表，它会按尚未清掉的旧账本把池补满，
# 留下没有账本额度对应的“孤儿单位”，下一次运行的 A = P + R 就会差出一个池容量。
TABLES = ['inventory_ledger', 'inventory_operations', 'reservation_units', 'reservations', 'reserved_quantities',
          'baseline_inventory_counter', 'baseline_reservations', 'baseline_reserved_quantities',
          'baseline_neighbor_orders']


def experiments():
    """每组：应用环境覆盖 + 若干配置；每个配置会重复 reps 次。"""
    groups = []
    e1 = []
    for vus in (10, 50, 200):
        for mode in ('pool', 'counter'):
            e1.append(dict(name=f'{mode}-vus{vus}', script='reserve-only.js', mode=mode, items=1, capacity=1000,
                           k6=dict(VUS=vus, ITEM_COUNT=1, CART_DIMENSIONS=1, QUANTITY=1)))
    groups.append(dict(id='E1', title='单热点 reserve-only：单位池 vs 单计数器', app_env={}, configs=e1))

    e2 = [dict(name=f'{mode}-vus50', script='lifecycle.js', mode=mode, items=10, capacity=1000,
               k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1, TTL_SECONDS=10))
          for mode in ('pool', 'counter')]
    groups.append(dict(id='E2', title='10 热点完整生命周期：单位池 vs 单计数器', app_env={}, configs=e2))

    for union_all in (False, True):
        groups.append(dict(
            id='E3', title='5 维度购物车：逐维度 SELECT vs UNION ALL',
            app_env={'STOCKHOLD_UNION_ALL_SELECT': 'true' if union_all else 'false'},
            configs=[dict(name=f'{"union-all" if union_all else "per-dimension"}-vus50', script='reserve-only.js',
                          mode='pool', items=10, capacity=1000,
                          k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=5, QUANTITY=1))]))

    e4 = [dict(name=f'hold-{mode}', script='connection-hold.js', mode='pool', items=10, capacity=1000,
               k6=dict(VUS=50, HOLD_VUS=16, HOLD_MODE=mode, HOLD_MILLIS=100, ITEM_COUNT=10))
          for mode in ('outside', 'connection')]
    groups.append(dict(id='E4', title='连接持有注入：事务外等待 vs 持有连接不执行 SQL', app_env={}, configs=e4))

    e5 = [dict(name=f'capacity{capacity}-vus50', script='reserve-only.js', mode='pool', items=1, capacity=capacity,
               k6=dict(VUS=50, ITEM_COUNT=1, CART_DIMENSIONS=1, QUANTITY=1))
          for capacity in (50, 1000)]
    groups.append(dict(id='E5', title='单热点池容量：50 vs 1000', app_env={}, configs=e5))

    def hot(name, mode='pool', vus=50, script='reserve-only.js', **extra):
        return dict(name=name, script=script, mode=mode, items=1, capacity=1000,
                    k6=dict(VUS=vus, ITEM_COUNT=1, CART_DIMENSIONS=1, QUANTITY=1, **extra))

    # E12：文章——InnoDB 线程并发度多年前被保守设置、从未复查，调高后消除了一个瓶颈。
    # 在数据库成为瓶颈的区间（64 连接、100 并发）比较保守值与不限制（0）。
    for concurrency in (1, 2, 4, 8, 0):
        groups.append(dict(id='E12', title='InnoDB 线程并发度：保守设置 vs 不限制',
                           app_env={'STOCKHOLD_DB_POOL_SIZE': '64'},
                           mysql_globals={'innodb_thread_concurrency': concurrency},
                           configs=[hot(f'pool-itc{concurrency}-vus100', vus=100)]))

    # E13：文章——部分读流量移到副本。每次预留伴随 4 次库存查询，strong 读主库、eventual 读副本。
    groups.append(dict(id='E13', title='读流量移到只读副本：strong（主库）vs eventual（副本）',
                       app_env={'STOCKHOLD_REPLICA_DB_URL': '${REPLICA_URL}'}, wait_replica=True,
                       configs=[dict(name=f'read-mix-{c}', script='read-mix.js', mode='pool', items=10, capacity=1000,
                                     k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1, READS=4,
                                             CONSISTENCY=c)) for c in ('strong', 'eventual')]))

    # E14：文章——跨结账把预留合并进一次 SKIP LOCKED 查询以减少连接使用。
    for batching in ('false', 'true'):
        groups.append(dict(id='E14', title='跨结账批量预留：关闭 vs 开启',
                           app_env={'STOCKHOLD_RESERVE_BATCHING_ENABLED': batching},
                           configs=[hot(f'batching-{"on" if batching == "true" else "off"}-vus{v}', vus=v)
                                    for v in (50, 200)]))

    # E15：文章——“安全的邻居”。邻居业务以固定到达率运行，比较叠加单位池 / 单计数器热点预留时邻居的延迟。
    groups.append(dict(id='E15', title='与其他业务共用数据库：邻居延迟', app_env={},
                       configs=[dict(name=name, script='neighbors.js', mode=mode, items=1, capacity=1000,
                                     k6=dict(NEIGHBOR_RATE=300, RESERVE_VUS=vus, ITEM_COUNT=1, CART_DIMENSIONS=1,
                                             QUANTITY=1))
                                for name, mode, vus in (('neighbors-only', 'pool', 0),
                                                        ('neighbors-with-pool', 'pool', 100),
                                                        ('neighbors-with-counter', 'counter', 100))]))

    # E15b：邻居跑在独立的应用进程（各自的连接池）上，只保留数据库层面的相互影响。
    groups.append(dict(id='E15b', title='与其他业务共用数据库：邻居在独立应用进程上', app_env={}, instances=2,
                       configs=[dict(name=name, script='neighbors.js', mode=mode, items=1, capacity=1000,
                                     k6=dict(NEIGHBOR_RATE=300, RESERVE_VUS=vus, ITEM_COUNT=1, CART_DIMENSIONS=1,
                                             QUANTITY=1, BASE_URLS=f'http://127.0.0.1:{BASE_PORT}',
                                             NEIGHBOR_BASE_URL=f'http://127.0.0.1:{BASE_PORT + 1}'))
                                for name, mode, vus in (('separate-neighbors-only', 'pool', 0),
                                                        ('separate-neighbors-with-pool', 'pool', 100),
                                                        ('separate-neighbors-with-counter', 'counter', 100))]))

    # E13b：读副本的延迟保护。副本新鲜时由副本服务；应用线程停住、延迟持续增长时全部回落主库，不出现 404。
    for stalled in (False, True):
        groups.append(dict(id='E13b', title='读副本的延迟保护：副本新鲜 vs 副本停滞',
                           app_env={'STOCKHOLD_REPLICA_DB_URL': '${REPLICA_URL}'}, wait_replica=True,
                           stall_replica=stalled,
                           configs=[dict(name=f'read-mix-eventual-{"stalled" if stalled else "fresh"}',
                                         script='read-mix.js', mode='pool', items=10, capacity=1000,
                                         k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1, READS=4,
                                                 CONSISTENCY='eventual'))]))

    # E17：到期清理。两个实例，60% 的预留等待到期（TTL 5 秒）；原实现（逐个扫描、每秒一轮）vs SKIP LOCKED 工作线程。
    for mode, env_overrides in (('scan', {'STOCKHOLD_EXPIRY_MODE': 'scan', 'STOCKHOLD_EXPIRY_INTERVAL_MILLIS': '1000'}),
                                ('skip-locked-single', {'STOCKHOLD_EXPIRY_CLAIM_BATCH_SIZE': '1'}),
                                ('skip-locked-batch50', {'STOCKHOLD_EXPIRY_CLAIM_BATCH_SIZE': '50'})):
        groups.append(dict(id='E17', title='到期清理：逐个扫描 vs SKIP LOCKED 逐个领取 vs SKIP LOCKED 批量领取',
                           app_env=env_overrides,
                           instances=2, measure_expiry=True,
                           configs=[dict(name=f'expiry-{mode}', script='lifecycle.js', mode='pool', items=10,
                                         capacity=1000,
                                         k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1, TTL_SECONDS=5,
                                                 CLAIM_SHARE=0.3, CANCEL_SHARE=0.1))]))

    # E16：补充期间等待的预留在池补满后立即继续（文章第 25 条）。同一批次内对照：退避（旧行为）vs 立即重试。
    for immediate in ('false', 'true'):
        groups.append(dict(id='E16', title='池补满即继续：退避 vs 立即重试',
                           app_env={'STOCKHOLD_RESERVE_REFILL_IMMEDIATE_RETRY': immediate},
                           configs=[hot(f'{"immediate" if immediate == "true" else "backoff"}-vus{v}', vus=v)
                                    for v in (10, 50, 200)]))

    # E7：连接数扫描，检验“单位池卡在连接池上”的推测；单计数器作对照（瓶颈应在行锁，不随连接数变化）。
    for size in (8, 16, 32, 64):
        configs = [dict(name=f'pool-hikari{size}-vus100', script='reserve-only.js', mode='pool', items=1,
                        capacity=1000, k6=dict(VUS=100, ITEM_COUNT=1, CART_DIMENSIONS=1, QUANTITY=1))]
        if size in (16, 64):
            configs.append(dict(name=f'counter-hikari{size}-vus100', script='reserve-only.js', mode='counter',
                                items=1, capacity=1000, k6=dict(VUS=100, ITEM_COUNT=1, CART_DIMENSIONS=1, QUANTITY=1)))
        groups.append(dict(id='E7', title='Hikari 连接数扫描：8 / 16 / 32 / 64', app_env={'STOCKHOLD_DB_POOL_SIZE': str(size)},
                           configs=configs))

    # E8：多个应用进程竞争同一个库；各实例的后台补充 / 过期任务同时运行。
    for instances in (1, 2, 3):
        groups.append(dict(id='E8', title='多应用实例：1 / 2 / 3 个进程（每个 16 连接）', app_env={}, instances=instances,
                           configs=[dict(name=f'{mode}-instances{instances}-vus150', script='reserve-only.js', mode=mode,
                                         items=1, capacity=1000,
                                         k6=dict(VUS=150, ITEM_COUNT=1, CART_DIMENSIONS=1, QUANTITY=1))
                                    for mode in ('pool', 'counter')]))
    return groups


# ---------------------------------------------------------------- 环境与进程

def load_env():
    env = {}
    for line in (ROOT / '.env').read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        if len(value) >= 2 and value[0] == value[-1] and value[0] in '"\'':
            value = value[1:-1]
        env[key] = value
    return env


def compose_cmd():
    if shutil.which('docker-compose'):
        return ['docker-compose']
    return ['docker', 'compose']


def mysql(sql, database='stockhold'):
    """以容器内 root 执行 SQL（密码取自容器环境变量，不经过本进程）。返回行列表。"""
    command = compose_cmd() + ['exec', '-T', 'mysql', 'sh', '-c',
                               f'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B {database}']
    result = subprocess.run(command, input=sql, capture_output=True, text=True, cwd=ROOT, check=True)
    return [line.split('\t') for line in result.stdout.splitlines() if line]


def http(method, path, body=None, headers=None, timeout=30, port=BASE_PORT):
    request = urllib.request.Request(f'http://127.0.0.1:{port}' + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', **(headers or {})})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read().decode()


class App:
    """启动 instances 个应用进程（端口 8080 起递增），全部健康后返回；退出时全部终止。"""

    def __init__(self, env, overrides, log_dir, name, instances=1, profiles='dev,benchmark'):
        self.env = {**os.environ, **env, **overrides}
        self.profiles = profiles
        self.log_dir = log_dir
        self.name = name
        self.ports = [BASE_PORT + i for i in range(instances)]
        self.processes = []

    def start_one(self, port):
        log = open(self.log_dir / f'app-{self.name}-{port}.log', 'a')
        env = {**self.env, 'STOCKHOLD_SERVER_PORT': str(port)}
        return subprocess.Popen(['java', '-jar', str(JAR), f'--spring.profiles.active={self.profiles}'],
                                env=env, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)

    def wait_healthy(self, port, process):
        for _ in range(120):
            try:
                if http('GET', '/actuator/health', timeout=2, port=port)[0] == 200:
                    return
            except OSError:
                pass
            if process.poll() is not None:
                raise RuntimeError(f'application on port {port} exited, see {self.log_dir}')
            time.sleep(1)
        raise RuntimeError(f'application on port {port} did not become healthy')

    def __enter__(self):
        global PORTS
        self.processes = [self.start_one(port) for port in self.ports]
        for port, process in zip(self.ports, self.processes):
            self.wait_healthy(port, process)
        PORTS = list(self.ports)
        return self

    def __exit__(self, *exc):
        global PORTS
        for process in self.processes:
            process.terminate()
        for process in self.processes:
            try:
                process.wait(30)
            except subprocess.TimeoutExpired:
                process.kill()
        PORTS = [BASE_PORT]


# ---------------------------------------------------------------- 数据准备与审计

def reset_tables():
    mysql('SET FOREIGN_KEY_CHECKS=0;' + ''.join(f'TRUNCATE TABLE {t};' for t in TABLES))


def seed(config, env, on_hand):
    items = config['items']
    if config['mode'] == 'pool':
        subprocess.run([str(ROOT / 'scripts' / 'seed.sh'), str(SHOP_ID), str(items), str(on_hand),
                        str(config['capacity'])], env={**os.environ, **env}, check=True,
                       capture_output=True, cwd=ROOT)
    else:
        for i in range(items):
            http('POST', f'/api/v1/benchmark/counter/shops/{SHOP_ID}/inventory/{FIRST_ITEM_ID + i}'
                         f'/locations/{LOCATION_ID}/seed?onHand={on_hand}',
                 headers={'X-Admin-Token': env['STOCKHOLD_ADMIN_TOKEN']})


def audit(config, env, on_hand):
    """结束时的 H/A/P/R 与不变量。池模式用 verify.sh 的同一审计接口；计数器模式直接核对明细。"""
    items = config['items']
    if config['mode'] == 'pool':
        rows = []
        for i in range(items):
            status, body = http('POST', '/api/v1/admin/invariants/check',
                                {'shopId': SHOP_ID, 'inventoryItemId': FIRST_ITEM_ID + i, 'locationId': LOCATION_ID},
                                headers={'X-Admin-Token': env['STOCKHOLD_ADMIN_TOKEN']})
            report = json.loads(body)
            rows.append(dict(item=FIRST_ITEM_ID + i, valid=report['valid'], H=report['onHandQuantity'],
                             A=report['allocatedQuantity'], P=report['poolAvailableQuantity'],
                             R=report['activeReservedQuantity'], violations=report['violations']))
        return dict(valid=all(r['valid'] for r in rows), dimensions=rows)
    rows = []
    for item, h, r, active, claimed in mysql(f"""
        SELECT c.inventory_item_id, c.on_hand_quantity, c.reserved_quantity,
          (SELECT COALESCE(SUM(q.quantity), 0) FROM baseline_reserved_quantities q
             JOIN baseline_reservations b ON b.shop_id = q.shop_id AND b.reservation_id = q.reservation_id
            WHERE q.shop_id = c.shop_id AND q.inventory_item_id = c.inventory_item_id
              AND q.location_id = c.location_id AND b.status = 'ACTIVE'),
          (SELECT COALESCE(SUM(q.quantity), 0) FROM baseline_reserved_quantities q
             JOIN baseline_reservations b ON b.shop_id = q.shop_id AND b.reservation_id = q.reservation_id
            WHERE q.shop_id = c.shop_id AND q.inventory_item_id = c.inventory_item_id
              AND q.location_id = c.location_id AND b.status = 'CLAIMED')
        FROM baseline_inventory_counter c WHERE c.shop_id = {SHOP_ID} ORDER BY 1;"""):
        h, r, active, claimed = int(h), int(r), int(active), int(claimed)
        valid = r == active and h == on_hand - claimed and 0 <= r <= h
        rows.append(dict(item=int(item), valid=valid, H=h, R=r, activeDetail=active, claimedDetail=claimed))
    return dict(valid=len(rows) == items and all(r['valid'] for r in rows), dimensions=rows)


# ---------------------------------------------------------------- 指标采集

METRIC_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)$')
KEEP = re.compile(r'^(stockhold_|hikaricp_connections_(acquire|usage|timeout))')


def prometheus():
    values = {}
    for port in PORTS:
        for key, value in prometheus_one(port).items():
            values[key] = values.get(key, 0.0) + value
    return values


def prometheus_one(port):
    _, body = http('GET', '/actuator/prometheus', port=port)
    values = {}
    for line in body.splitlines():
        match = METRIC_LINE.match(line)
        if match and KEEP.match(match.group(1)) and not match.group(1).endswith('_max') \
                and 'quantile' not in (match.group(2) or ''):
            labels = re.sub(r',?application="[^"]*"', '', match.group(2) or '').replace('{,', '{')
            values[match.group(1) + (labels if labels != '{}' else '')] = float(match.group(3))
    return values


def mysql_counters():
    rows = mysql("""
        SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status
         WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Com_commit', 'Com_rollback',
                                 'Questions', 'Com_select', 'Innodb_rows_inserted', 'Innodb_rows_deleted',
                                 'Innodb_rows_updated')
        UNION ALL
        SELECT NAME, COUNT FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks', 'lock_timeouts');""")
    return {name: float(value) for name, value in rows}


def delta(after, before):
    return {key: round(after[key] - before.get(key, 0.0), 6) for key in after
            if after[key] - before.get(key, 0.0) != 0}


class Sampler(threading.Thread):
    """测量窗口内约每 2 秒采样一次 Hikari pending、应用 CPU、MySQL Threads_running 与容器 CPU。"""

    def __init__(self, replica=False, overdue=False):
        super().__init__(daemon=True)
        self.replica = replica
        self.overdue = overdue
        self.stop = threading.Event()
        self.samples = []
        self.container = subprocess.run(compose_cmd() + ['ps', '-q', 'mysql'], capture_output=True, text=True,
                                        cwd=ROOT).stdout.strip()

    def actuator(self, name, port=BASE_PORT):
        _, body = http('GET', f'/actuator/metrics/{name}', timeout=5, port=port)
        return json.loads(body)['measurements'][0]['value']

    def run(self):
        while not self.stop.is_set():
            sample = {}
            try:
                # 多实例时 Hikari 指标为所有实例之和。
                sample['hikari_pending'] = sum(self.actuator('hikaricp.connections.pending', p) for p in PORTS)
                sample['hikari_active'] = sum(self.actuator('hikaricp.connections.active', p) for p in PORTS)
                sample['app_process_cpu'] = self.actuator('process.cpu.usage')
                sample['host_system_cpu'] = self.actuator('system.cpu.usage')
                sample['threads_running'] = float(mysql(
                    "SELECT VARIABLE_VALUE FROM performance_schema.global_status"
                    " WHERE VARIABLE_NAME = 'Threads_running'")[0][0])
                stats = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{.CPUPerc}}',
                                        self.container], capture_output=True, text=True, timeout=10).stdout
                sample['mysql_container_cpu_percent'] = float(stats.strip().rstrip('%') or 0)
                if self.replica:
                    sample['replica_lag_seconds'] = replica_lag_seconds()
                if self.overdue:
                    sample['overdue_active'] = float(mysql(OVERDUE_SQL)[0][0])
            except Exception as e:  # 采样失败不影响测量本身
                sample['error'] = str(e)
            self.samples.append(sample)
            self.stop.wait(2)

    def summary(self):
        result = {}
        for key in ('hikari_pending', 'hikari_active', 'app_process_cpu', 'host_system_cpu', 'threads_running',
                    'mysql_container_cpu_percent', 'replica_lag_seconds', 'overdue_active'):
            values = [s[key] for s in self.samples if s.get(key) is not None]
            if values:
                result[key] = dict(avg=round(sum(values) / len(values), 3), max=round(max(values), 3),
                                   samples=len(values))
        return result


# ---------------------------------------------------------------- 运行

def k6(config, env, duration, summary_path, quiet=True):
    # 配置里的 k6 变量优先（例如 E15b 把预留与邻居发往不同的应用进程）。
    variables = dict(MODE=config['mode'], SHOP_ID=SHOP_ID, FIRST_ITEM_ID=FIRST_ITEM_ID,
                     LOCATION_ID=LOCATION_ID, WARMUP='1s', DURATION=duration, ADMIN_TOKEN=env['STOCKHOLD_ADMIN_TOKEN'],
                     BASE_URLS=','.join(f'http://127.0.0.1:{port}' for port in PORTS))
    variables.update(config['k6'])
    command = ['k6', 'run', '--quiet', '--summary-export', str(summary_path)]
    for key, value in variables.items():
        command += ['-e', f'{key}={value}']
    command.append(str(ROOT / 'benchmarks' / 'k6' / config['script']))
    result = subprocess.run(command, capture_output=True, text=True, cwd=ROOT)
    return result.returncode, result.stdout[-4000:] + result.stderr[-4000:]


def k6_metrics(summary_path):
    """保留全部 k6 指标（含各脚本的自定义计数器，例如 read_ok、neighbor_ok）。"""
    metrics = json.loads(Path(summary_path).read_text())['metrics']
    return {name: {k: v for k, v in values.items() if k != 'thresholds'} for name, values in metrics.items()}


def replica_sql(sql):
    command = compose_cmd() + ['exec', '-T', 'mysql-replica', 'sh', '-c',
                               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B']
    result = subprocess.run(command, input=sql, capture_output=True, text=True, cwd=ROOT, check=True)
    return [line.split('\t') for line in result.stdout.splitlines() if line]


def wait_for_replica(timeout_seconds=600):
    """等副本应用完主库当前已执行的全部事务；返回等待秒数。"""
    executed = mysql('SELECT @@GLOBAL.gtid_executed;')[0][0].replace('\n', '')
    started = time.time()
    result = replica_sql(f"SELECT WAIT_FOR_EXECUTED_GTID_SET('{executed}', {timeout_seconds});")[0][0]
    if result != '0':
        raise RuntimeError(f'replica did not catch up within {timeout_seconds}s')
    return round(time.time() - started, 1)


OVERDUE_SQL = ("SELECT COUNT(*) FROM reservations WHERE status = 'ACTIVE'"
               " AND expires_at <= UTC_TIMESTAMP(6);")


def drain_seconds(timeout_seconds=300):
    """负载停止后，已到期仍为 ACTIVE 的积压清零所需的秒数；超时返回 None。"""
    started = time.time()
    while time.time() - started < timeout_seconds:
        if mysql(OVERDUE_SQL)[0][0] == '0':
            return round(time.time() - started, 1)
        time.sleep(1)
    return None


def replica_lag_seconds():
    for line in subprocess.run(compose_cmd() + ['exec', '-T', 'mysql-replica', 'sh', '-c',
                                                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SHOW REPLICA STATUS\\G"'],
                               capture_output=True, text=True, cwd=ROOT).stdout.splitlines():
        if 'Seconds_Behind_Source:' in line:
            value = line.split(':', 1)[1].strip()
            return None if value == 'NULL' else float(value)
    return None


class Phases:
    """记录一次运行各阶段的耗时（秒），便于定位运行为什么变慢。"""

    def __init__(self):
        self.seconds = {}
        self.mark = time.time()

    def done(self, name):
        now = time.time()
        self.seconds[name] = round(now - self.mark, 1)
        self.mark = now


def run_once(group, config, rep, env, args, out_dir, on_hand, hooks=None):
    """hooks 可选：start() 在测量窗口开始前调用，stop() 在结束后调用并返回附加到结果中的字典。"""
    phases = Phases()
    reset_tables()
    phases.done('reset')
    seed(config, env, on_hand)
    phases.done('seed')
    initial = audit(config, env, on_hand)
    phases.done('initial_audit')
    if not initial['valid']:
        # 起点不干净的运行不记录：它测到的是清库残留，而不是被测系统。
        raise RuntimeError(f'audit failed right after seeding: {initial}')
    scratch = out_dir / 'scratch'
    scratch.mkdir(exist_ok=True)
    replica_wait = wait_for_replica() if group.get('wait_replica') else None
    phases.done('replica_wait')
    if group.get('stall_replica'):
        # 停住副本的应用线程：复制延迟在测量窗口内持续增长，用来检验读副本的延迟保护。
        replica_sql('STOP REPLICA SQL_THREAD;')
    warm_code, warm_log = k6(config, env, args.warmup, scratch / 'warmup.json')
    phases.done('warmup')
    prom_before, db_before = prometheus(), mysql_counters()
    sampler = Sampler(replica=bool(group.get('wait_replica')), overdue=bool(group.get('measure_expiry')))
    sampler.start()
    if hooks:
        hooks.start()
    started = time.time()
    code, log = k6(config, env, args.duration, scratch / 'measure.json')
    elapsed = time.time() - started
    phases.done('measure')
    # 测量窗口被拉长（例如系统睡眠、进程被冻结）时，按秒计算的速率都不可信。
    suspect = elapsed > float(args.duration.rstrip('s')) + 45
    if suspect:
        print(f'    WARNING: k6 took {elapsed:.0f}s for a {args.duration} window; this run is unreliable', flush=True)
    extra = hooks.stop() if hooks else {}
    sampler.stop.set()
    sampler.join(15)
    prom_after, db_after = prometheus(), mysql_counters()
    phases.done('samplers')
    drained = drain_seconds() if group.get('measure_expiry') else None
    phases.done('drain_wait')
    if group.get('stall_replica'):
        replica_sql('START REPLICA SQL_THREAD;')
    final_audit = audit(config, env, on_hand)
    phases.done('final_audit')
    return dict(
        expiry_drain_after_stop_s=drained, suspect_timing=suspect,
        group=group['id'], title=group['title'], config=config['name'], rep=rep, app_env=group['app_env'],
        instances=len(PORTS),
        k6_env=config['k6'], mode=config['mode'], script=config['script'], items=config['items'],
        capacity=config['capacity'], seed_on_hand=on_hand, duration=args.duration, wall_seconds=round(elapsed, 1),
        k6_exit=code, warmup_exit=warm_code, k6=k6_metrics(scratch / 'measure.json'),
        app_metrics_delta=delta(prom_after, prom_before), mysql_delta=delta(db_after, db_before),
        samples=sampler.summary(), audit=final_audit, extra=extra, phase_seconds=phases.seconds,
        replica_catch_up_wait_s=replica_wait,
        k6_log_tail=log if code != 0 else '')


def keep_awake():
    """macOS 上在本进程存活期间阻止系统睡眠：睡眠会冻结虚拟机、JVM 与 k6，把墙钟时间拉长数十倍，
    曾让一整夜的测量全部失效。caffeinate -w 在本进程退出时自动释放。"""
    if sys.platform == 'darwin' and shutil.which('caffeinate'):
        subprocess.Popen(['caffeinate', '-dims', '-w', str(os.getpid())],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def environment(env):
    def sh(command):
        try:
            return subprocess.run(command, capture_output=True, text=True, shell=True, cwd=ROOT).stdout.strip()
        except OSError:
            return ''
    names = ['version', 'transaction_isolation', 'innodb_thread_concurrency', 'max_connections',
             'innodb_lock_wait_timeout', 'innodb_buffer_pool_size']
    variables = dict(zip(names, mysql('SELECT ' + ', '.join('@@' + n for n in names) + ';')[0]))
    return dict(
        host_cpu=sh('sysctl -n machdep.cpu.brand_string'), host_cpus=sh('sysctl -n hw.ncpu'),
        host_memory_bytes=sh('sysctl -n hw.memsize'), os=sh('sw_vers -productVersion'),
        docker_vm=sh("docker info --format '{{.NCPU}} CPU / {{.MemTotal}} bytes / {{.OperatingSystem}}'"),
        java=sh('java -version 2>&1 | head -1'), k6=sh('k6 version'), commit=sh('git rev-parse --short HEAD'),
        dirty=bool(sh('git status --porcelain')), mysql=variables,
        hikari_max=env.get('STOCKHOLD_DB_POOL_SIZE', '16'), reserve_max_concurrent='256 (benchmark profile)')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--only', default='', help='逗号分隔的实验 ID，例如 E1,E3')
    parser.add_argument('--reps', type=int, default=3)
    parser.add_argument('--warmup', default='10s')
    parser.add_argument('--duration', default='60s')
    parser.add_argument('--on-hand', type=int, default=10_000_000, help='每个维度的初始库存，保证测量窗口内不耗尽')
    args = parser.parse_args()
    keep_awake()

    env = load_env()
    args.out.mkdir(parents=True, exist_ok=True)
    only = {x.strip() for x in args.only.split(',') if x.strip()}
    (args.out / 'environment.json').write_text(json.dumps(environment(env), ensure_ascii=False, indent=2))
    for group in experiments():
        if only and group['id'] not in only:
            continue
        replica_url = env['STOCKHOLD_DB_URL'].replace(':33306/', ':' + env.get('STOCKHOLD_REPLICA_DB_PORT', '33307') + '/')
        overrides = {k: v.replace('${REPLICA_URL}', replica_url) for k, v in group['app_env'].items()}
        (args.out / 'scratch').mkdir(exist_ok=True)
        saved = apply_mysql_globals(group.get('mysql_globals', {}))
        try:
            run_group(group, env, overrides, args)
        finally:
            apply_mysql_globals(saved)
    shutil.rmtree(args.out / 'scratch', ignore_errors=True)


def apply_mysql_globals(settings):
    """SET GLOBAL 给定变量并返回原值，供组结束后恢复。"""
    saved = {}
    for name, value in settings.items():
        saved[name] = mysql(f'SELECT @@GLOBAL.{name};')[0][0]
        mysql(f'SET GLOBAL {name} = {int(value)};')
    return saved


def run_group(group, env, overrides, args):
    logs = args.out / 'app-logs'
    logs.mkdir(exist_ok=True)
    started = time.time()
    with App(env, overrides, logs, group['id'], group.get('instances', 1)):
        print(f"    app start {time.time() - started:.1f}s", flush=True)
        for config in group['configs']:
            for rep in range(1, args.reps + 1):
                target = args.out / f"{group['id']}-{config['name']}-r{rep}.json"
                print(f"[{time.strftime('%H:%M:%S')}] {group['id']} {config['name']} rep {rep}", flush=True)
                result = run_once(group, config, rep, env, args, args.out, args.on_hand)
                result['mysql_globals'] = group.get('mysql_globals', {})
                target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
                reserve = result['k6'].get('reserve_created', {})
                print(f"    created/s={reserve.get('rate', 0):.1f} audit_valid={result['audit']['valid']}"
                      f" k6_exit={result['k6_exit']} phases={result['phase_seconds']}", flush=True)
        stopping = time.time()
    print(f"    app stop {time.time() - stopping:.1f}s", flush=True)


if __name__ == '__main__':
    sys.exit(main())
