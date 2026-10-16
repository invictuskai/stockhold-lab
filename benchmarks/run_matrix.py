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
          'baseline_inventory_counter', 'baseline_reservations', 'baseline_reserved_quantities']


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
                                 'Questions', 'Innodb_rows_inserted', 'Innodb_rows_deleted', 'Innodb_rows_updated')
        UNION ALL
        SELECT NAME, COUNT FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks', 'lock_timeouts');""")
    return {name: float(value) for name, value in rows}


def delta(after, before):
    return {key: round(after[key] - before.get(key, 0.0), 6) for key in after
            if after[key] - before.get(key, 0.0) != 0}


class Sampler(threading.Thread):
    """测量窗口内约每 2 秒采样一次 Hikari pending、应用 CPU、MySQL Threads_running 与容器 CPU。"""

    def __init__(self):
        super().__init__(daemon=True)
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
            except Exception as e:  # 采样失败不影响测量本身
                sample['error'] = str(e)
            self.samples.append(sample)
            self.stop.wait(2)

    def summary(self):
        result = {}
        for key in ('hikari_pending', 'hikari_active', 'app_process_cpu', 'host_system_cpu', 'threads_running',
                    'mysql_container_cpu_percent'):
            values = [s[key] for s in self.samples if key in s]
            if values:
                result[key] = dict(avg=round(sum(values) / len(values), 3), max=round(max(values), 3),
                                   samples=len(values))
        return result


# ---------------------------------------------------------------- 运行

def k6(config, env, duration, summary_path, quiet=True):
    variables = dict(config['k6'], MODE=config['mode'], SHOP_ID=SHOP_ID, FIRST_ITEM_ID=FIRST_ITEM_ID,
                     LOCATION_ID=LOCATION_ID, WARMUP='1s', DURATION=duration, ADMIN_TOKEN=env['STOCKHOLD_ADMIN_TOKEN'],
                     BASE_URLS=','.join(f'http://127.0.0.1:{port}' for port in PORTS))
    command = ['k6', 'run', '--quiet', '--summary-export', str(summary_path)]
    for key, value in variables.items():
        command += ['-e', f'{key}={value}']
    command.append(str(ROOT / 'benchmarks' / 'k6' / config['script']))
    result = subprocess.run(command, capture_output=True, text=True, cwd=ROOT)
    return result.returncode, result.stdout[-4000:] + result.stderr[-4000:]


def k6_metrics(summary_path):
    metrics = json.loads(Path(summary_path).read_text())['metrics']
    keep = {}
    for name, values in metrics.items():
        if name.startswith(('http_req_duration{op:', 'http_reqs', 'reserve_', 'checks', 'iterations',
                            'http_req_failed')):
            keep[name] = {k: v for k, v in values.items() if k != 'thresholds'}
    return keep


def run_once(group, config, rep, env, args, out_dir, on_hand, hooks=None):
    """hooks 可选：start() 在测量窗口开始前调用，stop() 在结束后调用并返回附加到结果中的字典。"""
    reset_tables()
    seed(config, env, on_hand)
    initial = audit(config, env, on_hand)
    if not initial['valid']:
        # 起点不干净的运行不记录：它测到的是清库残留，而不是被测系统。
        raise RuntimeError(f'audit failed right after seeding: {initial}')
    scratch = out_dir / 'scratch'
    scratch.mkdir(exist_ok=True)
    warm_code, warm_log = k6(config, env, args.warmup, scratch / 'warmup.json')
    prom_before, db_before = prometheus(), mysql_counters()
    sampler = Sampler()
    sampler.start()
    if hooks:
        hooks.start()
    started = time.time()
    code, log = k6(config, env, args.duration, scratch / 'measure.json')
    elapsed = time.time() - started
    extra = hooks.stop() if hooks else {}
    sampler.stop.set()
    sampler.join(15)
    prom_after, db_after = prometheus(), mysql_counters()
    return dict(
        group=group['id'], title=group['title'], config=config['name'], rep=rep, app_env=group['app_env'],
        instances=len(PORTS),
        k6_env=config['k6'], mode=config['mode'], script=config['script'], items=config['items'],
        capacity=config['capacity'], seed_on_hand=on_hand, duration=args.duration, wall_seconds=round(elapsed, 1),
        k6_exit=code, warmup_exit=warm_code, k6=k6_metrics(scratch / 'measure.json'),
        app_metrics_delta=delta(prom_after, prom_before), mysql_delta=delta(db_after, db_before),
        samples=sampler.summary(), audit=audit(config, env, on_hand), extra=extra,
        k6_log_tail=log if code != 0 else '')


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

    env = load_env()
    args.out.mkdir(parents=True, exist_ok=True)
    only = {x.strip() for x in args.only.split(',') if x.strip()}
    (args.out / 'environment.json').write_text(json.dumps(environment(env), ensure_ascii=False, indent=2))
    for group in experiments():
        if only and group['id'] not in only:
            continue
        overrides = {k: v for k, v in group['app_env'].items()}
        (args.out / 'scratch').mkdir(exist_ok=True)
        with App(env, overrides, args.out / 'scratch', group['id'], group.get('instances', 1)):
            for config in group['configs']:
                for rep in range(1, args.reps + 1):
                    target = args.out / f"{group['id']}-{config['name']}-r{rep}.json"
                    print(f"[{time.strftime('%H:%M:%S')}] {group['id']} {config['name']} rep {rep}", flush=True)
                    result = run_once(group, config, rep, env, args, args.out, args.on_hand)
                    target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
                    reserve = result['k6'].get('reserve_created', {})
                    print(f"    created/s={reserve.get('rate', 0):.1f} audit_valid={result['audit']['valid']}"
                          f" k6_exit={result['k6_exit']}", flush=True)
    shutil.rmtree(args.out / 'scratch', ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
