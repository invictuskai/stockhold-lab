#!/usr/bin/env python3
"""三个演练，补齐压测矩阵之外的验证项。复用 run_matrix.py 的启停、清库、播种、采样与审计。

    python3 benchmarks/drills.py crash     --out benchmarks/reports/raw/<日期>-drills
    python3 benchmarks/drills.py proxysql  --out benchmarks/reports/raw/<日期>-drills
    python3 benchmarks/drills.py migration --out benchmarks/reports/raw/<日期>-drills

crash：两个应用实例跑完整生命周期，中途对一个实例 kill -9 并重启；客户端换实例用同一幂等键重试。
proxysql：应用经 ProxySQL 连接 MySQL，比较应用侧（Hikari）与代理侧（后端连接）的按业务标签归因。
migration：两个实例经迁移路由跑完整生命周期，按时间表切换 Redis / MySQL 事实来源并回切，最后核对两系统一致。
"""
import argparse
import importlib.util
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('run_matrix', HERE / 'run_matrix.py')
rm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rm)

ROOT = rm.ROOT
ON_HAND = 1_000_000


def k6_process(script, env, variables, summary_path):
    """k6 的输出直接写文件：若经管道而又不及时读取，缓冲区写满后 k6 的 VU 会阻塞在写日志上，
    在故障演练中表现为请求“挂起”数十秒（曾因此误判过一次）。"""
    command = ['k6', 'run', '--quiet', '--summary-export', str(summary_path)]
    merged = dict(SHOP_ID=rm.SHOP_ID, FIRST_ITEM_ID=rm.FIRST_ITEM_ID, LOCATION_ID=rm.LOCATION_ID, WARMUP='1s',
                  BASE_URLS=','.join(f'http://127.0.0.1:{p}' for p in rm.PORTS),
                  ADMIN_TOKEN=env['STOCKHOLD_ADMIN_TOKEN'], **variables)
    for key, value in merged.items():
        command += ['-e', f'{key}={value}']
    command.append(str(ROOT / 'benchmarks' / 'k6' / script))
    log_path = Path(summary_path).with_suffix('.log')
    process = subprocess.Popen(command, stdout=open(log_path, 'w'), stderr=subprocess.STDOUT, cwd=ROOT)
    process.log_path = log_path
    return process


def finish(process):
    process.wait()
    return Path(process.log_path).read_text()


def counters(summary_path):
    metrics = json.loads(Path(summary_path).read_text())['metrics']
    return {name: values.get('count', values.get('value')) for name, values in metrics.items()
            if 'count' in values or name == 'checks'}


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


# ---------------------------------------------------------------- crash

def crash(args, env):
    config = dict(mode='pool', items=10, capacity=1000)
    ttl = 10
    rm.reset_tables()
    scratch = args.out / 'scratch'
    with rm.App(env, {}, scratch, 'crash', instances=2) as app:
        rm.seed(config, env, ON_HAND)
        assert rm.audit(config, env, ON_HAND)['valid'], 'dirty start'
        prom_before = rm.prometheus()
        k6 = k6_process('crash-lifecycle.js', env, dict(VUS=50, DURATION=f'{args.seconds}s', TTL_SECONDS=ttl,
                                                        ITEM_COUNT=10), scratch / 'crash.json')
        started = time.time()
        time.sleep(args.seconds / 3)
        victim_port = app.ports[1]
        os.kill(app.processes[1].pid, signal.SIGKILL)
        killed_at = round(time.time() - started, 1)
        log(f'kill -9 instance on port {victim_port} at t={killed_at}s')
        time.sleep(args.seconds / 4)
        app.processes[1] = app.start_one(victim_port)
        app.wait_healthy(victim_port, app.processes[1])
        restarted_at = round(time.time() - started, 1)
        log(f'instance on port {victim_port} healthy again at t={restarted_at}s')
        k6_output = finish(k6)
        failures = [line for line in k6_output.splitlines() if 'follow-up failed' in line]
        log(f'k6 finished (exit {k6.returncode}); waiting {ttl + 8}s for expiry to catch up')
        time.sleep(ttl + 8)
        # 重启后的实例从零开始计数，指标差值只作参考；正确性以数据库审计为准。
        prom_after = rm.prometheus()
        result = dict(
            experiment='crash', seconds=args.seconds, killed_at=killed_at, restarted_at=restarted_at,
            victim_port=victim_port, k6_exit=k6.returncode, k6=counters(scratch / 'crash.json'),
            audit=rm.audit(config, env, ON_HAND),
            database=dict(
                status_counts=dict(rm.mysql('SELECT status, COUNT(*) FROM reservations GROUP BY status;')),
                stale_active=int(rm.mysql("SELECT COUNT(*) FROM reservations WHERE status = 'ACTIVE'"
                                          " AND expires_at < UTC_TIMESTAMP(6) - INTERVAL 3 SECOND;")[0][0]),
                headers_without_lines=int(rm.mysql(
                    'SELECT COUNT(*) FROM reservations r WHERE NOT EXISTS (SELECT 1 FROM reserved_quantities q'
                    ' WHERE q.shop_id = r.shop_id AND q.reservation_id = r.reservation_id);')[0][0]),
                lines_without_header=int(rm.mysql(
                    'SELECT COUNT(*) FROM reserved_quantities q WHERE NOT EXISTS (SELECT 1 FROM reservations r'
                    ' WHERE r.shop_id = q.shop_id AND r.reservation_id = q.reservation_id);')[0][0]),
                orphan_units=int(rm.mysql(
                    'SELECT COUNT(*) FROM reservation_units u WHERE NOT EXISTS (SELECT 1 FROM inventory_ledger l'
                    ' WHERE l.shop_id = u.shop_id AND l.inventory_item_id = u.inventory_item_id'
                    ' AND l.location_id = u.location_id);')[0][0])),
            app_metrics_delta=rm.delta(prom_after, prom_before), k6_log_tail=k6_output[-3000:],
            follow_up_failures=failures[:100])
    k6c = result['k6']
    acknowledged = sum(k6c.get(name, 0) or 0 for name in (
        'reserve_created_first_try', 'reserve_created_after_retry', 'reserve_replayed_after_retry'))
    total = sum(int(v) for v in result['database']['status_counts'].values())
    # 每次迭代一个新幂等键：数据库中的预留数应恰好等于客户端最终确认成功的数量。
    result['reservations_in_db_minus_acknowledged'] = total - acknowledged
    return result


# ---------------------------------------------------------------- proxysql

class ProxyAdmin:
    def __init__(self, env):
        self.password = env.get('STOCKHOLD_PROXYSQL_ADMIN_PASSWORD') or env['STOCKHOLD_DB_PASSWORD']

    def command(self):
        return rm.compose_cmd() + ['exec', '-T', '-e', f'MYSQL_PWD={self.password}', 'mysql',
                                   'mysql', '-h', 'proxysql', '-P6032', '-uradmin', '-N', '-B']

    def query(self, sql):
        result = subprocess.run(self.command() + ['-e', sql], capture_output=True, text=True, cwd=ROOT, check=True)
        return [line.split('\t') for line in result.stdout.splitlines() if line]


TAG = re.compile(r'conn_tag:([a-z_]+)')


def tag_of(text):
    match = TAG.search(text or '')
    return match.group(1) if match else None


class ProxyHooks:
    """测量窗口内：
    - 用 ProxySQL 的查询事件日志重建每个客户端会话的事务：事务从第一条语句到 COMMIT / ROLLBACK
      （或结束它的 SET autocommit=1）期间一直占着后端连接，整段时间归给事务中第一条带标签语句的业务标签；
      自动提交的单条语句按其执行时间归因。这对应文章中“ProxySQL 解析标签、测量每个调用方持有连接的时间”。
      （会话表采样做不到：事务内空闲时 ProxySQL 不显示属于哪段代码。）
    - 约每 100 ms 采样一次后端连接池的使用数。
    - 代理侧的查询摘要（按标签的语句数与执行时间）。"""

    EVENTS_DIR = '/tmp/stockhold-events'

    def __init__(self, admin, seconds):
        self.admin = admin
        self.seconds = seconds
        self.process = None
        self.lines = []
        self.reader = None
        self.started = None

    def start(self):
        subprocess.run(rm.compose_cmd() + ['exec', '-T', 'proxysql', 'sh', '-c',
                                           f'mkdir -p {self.EVENTS_DIR} && rm -f {self.EVENTS_DIR}/*'],
                       cwd=ROOT, check=True, capture_output=True)
        self.admin.query(f"SET mysql-eventslog_filename='{self.EVENTS_DIR}/q'; SET mysql-eventslog_format=2;"
                         " SET mysql-eventslog_default_log=1; LOAD MYSQL VARIABLES TO RUNTIME; PROXYSQL FLUSH LOGS;"
                         " SELECT COUNT(*) FROM stats_mysql_query_digest_reset")
        loop = (f'end=$(( $(date +%s) + {self.seconds} )); while [ $(date +%s) -lt $end ]; do '
                'mysql -h proxysql -P6032 -uradmin -N -B -e "'
                "SELECT 'P', SUM(ConnUsed), SUM(ConnFree) FROM stats_mysql_connection_pool;"
                '"; sleep 0.1; done')
        self.process = subprocess.Popen(
            rm.compose_cmd() + ['exec', '-T', '-e', f'MYSQL_PWD={self.admin.password}', 'mysql', 'sh', '-c', loop],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, cwd=ROOT)
        self.reader = threading.Thread(target=lambda: self.lines.extend(self.process.stdout), daemon=True)
        self.reader.start()
        self.started = time.time()

    def stop(self):
        self.process.wait(30)
        self.reader.join(10)
        window = time.time() - self.started
        self.admin.query('SET mysql-eventslog_default_log=0; LOAD MYSQL VARIABLES TO RUNTIME; PROXYSQL FLUSH LOGS;')
        digests = {}
        for text, count, sum_time in self.admin.query(
                'SELECT digest_text, count_star, sum_time FROM stats_mysql_query_digest'):
            tag = tag_of(text) or 'untagged'
            entry = digests.setdefault(tag, dict(statements=0, sql_seconds=0.0))
            entry['statements'] += int(count)
            entry['sql_seconds'] += int(sum_time) / 1e6
        hold, transactions, events = self.transaction_hold()
        used = [int(p[1]) for p in (line.rstrip('\n').split('\t') for line in self.lines)
                if p[0] == 'P' and len(p) >= 2 and p[1] not in ('', 'NULL')]
        return dict(proxy_digest_by_tag=digests, proxy_events=events,
                    proxy_backend_seconds_per_second_by_tag={k: round(v / window, 3) for k, v in sorted(hold.items())},
                    proxy_transactions_by_tag=transactions,
                    proxy_backend_conn_used=dict(avg=round(sum(used) / len(used), 2) if used else None,
                                                 max=max(used) if used else None))

    def transaction_hold(self):
        """从事件日志按会话重建事务，返回（各标签的后端占用秒数, 各标签的事务数, 事件数）。"""
        raw = subprocess.run(rm.compose_cmd() + ['exec', '-T', 'proxysql', 'sh', '-c',
                                                 f'cat {self.EVENTS_DIR}/q.* 2>/dev/null; rm -f {self.EVENTS_DIR}/*'],
                             cwd=ROOT, capture_output=True, text=True).stdout
        sessions = {}
        events = 0
        for line in raw.splitlines():
            if not line.startswith('{'):
                continue
            try:
                event = json.loads(line)
            except ValueError:
                continue
            events += 1
            sessions.setdefault(event['thread_id'], []).append(
                (event['starttime_timestamp_us'], event['endtime_timestamp_us'], event.get('query', '')))
        hold, transactions = {}, {}

        def charge(tag, micros, transaction):
            tag = tag or 'untagged'
            hold[tag] = hold.get(tag, 0.0) + micros / 1e6
            if transaction:
                transactions[tag] = transactions.get(tag, 0) + 1

        for timeline in sessions.values():
            timeline.sort()
            autocommit, tx_start, tx_tag = True, None, None
            for begin, finish, query in timeline:
                text = query.strip().lower()
                if text.startswith('set autocommit=0') or text in ('begin', 'start transaction'):
                    autocommit = False
                    continue
                ends = text in ('commit', 'rollback') or text.startswith('set autocommit=1')
                if ends:
                    if tx_start is not None:
                        charge(tx_tag, finish - tx_start, True)
                    tx_start, tx_tag = None, None
                    if text.startswith('set autocommit=1'):
                        autocommit = True
                    continue
                if autocommit:
                    charge(tag_of(query), finish - begin, False)
                    continue
                if tx_start is None:
                    tx_start = begin
                if tx_tag is None:
                    tx_tag = tag_of(query)
        return hold, transactions, events


def start_proxysql(env):
    subprocess.run(rm.compose_cmd() + ['--profile', 'proxysql', 'up', '-d', 'proxysql'], cwd=ROOT, check=True,
                   capture_output=True)
    admin = ProxyAdmin(env)
    for _ in range(60):
        try:
            admin.query('SELECT 1')
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    port = env.get('STOCKHOLD_PROXYSQL_PORT', '36033')
    url = env['STOCKHOLD_DB_URL'].replace(':33306/', f':{port}/')
    assert url != env['STOCKHOLD_DB_URL'], 'unexpected STOCKHOLD_DB_URL format'
    return admin, url


def run_proxy_group(args, env, admin, group, configs, prefix):
    seconds = int(args.duration.rstrip('s'))
    runs = 0
    with rm.App(env, group['app_env'], args.out / 'scratch', group['id']):
        for config in configs:
            for rep in range(1, args.reps + 1):
                log(f"{group['id']} {config['name']} rep {rep}")
                result = rm.run_once(group, config, rep, env, args, args.out, ON_HAND,
                                     hooks=ProxyHooks(admin, seconds))
                result['proxysql_version'] = admin.query(
                    "SELECT variable_value FROM global_variables WHERE variable_name = 'admin-version'")[0][0]
                result['proxysql_backend_max_connections'] = int(admin.query(
                    'SELECT MAX(max_connections) FROM runtime_mysql_servers')[0][0])
                (args.out / f"{prefix}-{config['name']}-r{rep}.json").write_text(
                    json.dumps(result, ensure_ascii=False, indent=2))
                runs += 1
    return runs


def hold_config(name, mode):
    return dict(name=name, script='connection-hold.js', mode='pool', items=10, capacity=1000,
                k6=dict(VUS=50, HOLD_VUS=16, HOLD_MODE=mode, HOLD_MILLIS=100, ITEM_COUNT=10))


def proxysql(args, env):
    admin, url = start_proxysql(env)
    configs = [
        dict(name='proxy-reserve-vus50', script='reserve-only.js', mode='pool', items=10, capacity=1000,
             k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1)),
        hold_config('proxy-hold-outside', 'outside'),
        hold_config('proxy-hold-connection', 'connection'),
    ]
    group = dict(id='E10', title='经 ProxySQL：应用侧与代理侧的连接归因', app_env={'STOCKHOLD_DB_URL': url})
    return dict(experiment='proxysql', runs=run_proxy_group(args, env, admin, group, configs, 'E10'))


def proxysql_exhaustion(args, env):
    """文章的排障场景：客户端连接（Hikari 32）多于代理可用的后端连接（16），后端层耗尽；
    元凶是结账路径上其他代码的长事务（checkout_completion），按标签归因后定位、移除即恢复。"""
    admin, url = start_proxysql(env)
    admin.query('UPDATE mysql_servers SET max_connections = 16; LOAD MYSQL SERVERS TO RUNTIME;')
    try:
        configs = [
            dict(name='exhaust-reserve-only', script='reserve-only.js', mode='pool', items=10, capacity=1000,
                 k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1)),
            hold_config('exhaust-hold-transaction', 'transaction'),
            hold_config('exhaust-hold-connection', 'connection'),
        ]
        group = dict(id='E10X', title='ProxySQL 后端连接耗尽：长事务元凶的定位',
                     app_env={'STOCKHOLD_DB_URL': url, 'STOCKHOLD_DB_POOL_SIZE': '32'})
        return dict(experiment='proxysql-exhaustion',
                    runs=run_proxy_group(args, env, admin, group, configs, 'E10X'))
    finally:
        admin.query('UPDATE mysql_servers SET max_connections = 64; LOAD MYSQL SERVERS TO RUNTIME;')


# ---------------------------------------------------------------- migration

def migration(args, env):
    subprocess.run(rm.compose_cmd() + ['--profile', 'migration', 'up', '-d', 'redis'], cwd=ROOT, check=True,
                   capture_output=True)
    subprocess.run(rm.compose_cmd() + ['exec', '-T', 'redis', 'redis-cli', 'FLUSHALL'], cwd=ROOT, check=True,
                   capture_output=True)
    rm.reset_tables()
    config = dict(mode='pool', items=10, capacity=1000)
    ttl = 10
    token = {'X-Admin-Token': env['STOCKHOLD_ADMIN_TOKEN']}
    shop = rm.SHOP_ID
    scratch = args.out / 'scratch'
    # 时间表（占总时长的比例）：Redis 为主 -> MySQL 为主 -> 回切到 Redis -> 再切回 MySQL。
    schedule = [(0.0, 'REDIS_PRIMARY'), (1 / 3, 'MYSQL_PRIMARY'), (2 / 3, 'REDIS_PRIMARY'), (5 / 6, 'MYSQL_PRIMARY')]
    with rm.App(env, {}, scratch, 'migration', instances=2, profiles='dev,benchmark,migration'):
        rm.seed(config, env, ON_HAND)
        for i in range(config['items']):
            rm.http('POST', f'/api/v1/migration/shops/{shop}/inventory/{rm.FIRST_ITEM_ID + i}/locations/'
                            f'{rm.LOCATION_ID}/legacy-seed?available={ON_HAND}', headers=token)

        def set_mode(mode):
            rm.http('PUT', f'/api/v1/migration/shops/{shop}/mode', {'mode': mode}, headers=token)

        set_mode(schedule[0][1])
        phases = []
        snapshot = rm.prometheus()
        k6 = k6_process('migration.js', env, dict(VUS=50, DURATION=f'{args.seconds}s', TTL_SECONDS=ttl,
                                                  ITEM_COUNT=10), scratch / 'migration.json')
        started = time.time()
        for index, (fraction, mode) in enumerate(schedule):
            if index > 0:
                time.sleep(max(0.0, started + fraction * args.seconds - time.time()))
                now = rm.prometheus()
                phases.append(dict(mode=schedule[index - 1][1], metrics=rm.delta(now, snapshot)))
                snapshot = now
                set_mode(mode)
                log(f'mode -> {mode} at t={time.time() - started:.1f}s')
        k6_output = finish(k6)
        now = rm.prometheus()
        phases.append(dict(mode=schedule[-1][1], metrics=rm.delta(now, snapshot)))
        stopped = time.time()
        log(f'k6 finished (exit {k6.returncode}); polling until both systems converge')
        items = ','.join(str(rm.FIRST_ITEM_ID + i) for i in range(config['items']))
        overdue_sql = ("SELECT COUNT(*) FROM reservations WHERE status = 'ACTIVE'"
                       " AND expires_at < UTC_TIMESTAMP(6);")
        # 两系统各自到期清理，静止后应收敛到完全相同的状态；记录收敛所需时间与首次检查时的积压。
        convergence = []
        while True:
            _, consistency = rm.http('GET', f'/api/v1/migration/shops/{shop}/consistency?items={items}'
                                            f'&locationId={rm.LOCATION_ID}', headers=token)
            parsed = json.loads(consistency)
            convergence.append(dict(after_s=round(time.time() - stopped, 1), consistent=parsed['consistent'],
                                    redis=parsed['redisStatusCounts'], mysql=parsed['mysqlStatusCounts'],
                                    mysql_overdue_active=int(rm.mysql(overdue_sql)[0][0])))
            if parsed['consistent'] or time.time() - stopped > 120:
                break
            time.sleep(5)
        log(f"consistent={parsed['consistent']} after {convergence[-1]['after_s']}s")
        mismatches = []
        for port in rm.PORTS:
            _, body = rm.http('GET', '/api/v1/migration/mismatches', headers=token, port=port)
            mismatches += json.loads(body)
        return dict(experiment='migration', seconds=args.seconds, schedule=schedule, k6_exit=k6.returncode,
                    k6=counters(scratch / 'migration.json'), phases=phases,
                    consistency=parsed, convergence=convergence, mysql_audit=rm.audit(config, env, ON_HAND),
                    mismatch_count=len(mismatches), mismatch_samples=mismatches[:50],
                    k6_log_tail=k6_output[-3000:])


def rollout(args, env):
    """文章：按 pod 逐步上线，从低流量到最高流量的商家。每个店铺代表一个 pod；所有店铺先进入影子阶段，
    然后按流量从低到高逐个切换为 MySQL 为主，切换前要求该店铺在观察窗口内影子比对零不一致，否则停止推进。"""
    subprocess.run(rm.compose_cmd() + ['--profile', 'migration', 'up', '-d', 'redis'], cwd=ROOT, check=True,
                   capture_output=True)
    subprocess.run(rm.compose_cmd() + ['exec', '-T', 'redis', 'redis-cli', 'FLUSHALL'], cwd=ROOT, check=True,
                   capture_output=True)
    rm.reset_tables()  # 旧系统账本由 legacy-seed 按维度覆盖写入，无需单独清理
    shops = [(5001, 1), (5002, 2), (5003, 4), (5004, 8)]  # (店铺, 流量权重)，按流量从低到高
    items, ttl, window = 10, 10, args.seconds / (len(shops) + 2)
    token = {'X-Admin-Token': env['STOCKHOLD_ADMIN_TOKEN']}
    scratch = args.out / 'scratch'
    timeline, gates = [], []
    with rm.App(env, {}, scratch, 'rollout', instances=2, profiles='dev,benchmark,migration'):
        for shop, _ in shops:
            subprocess.run([str(ROOT / 'scripts' / 'seed.sh'), str(shop), str(items), str(ON_HAND), '1000'],
                           env={**os.environ, **env}, check=True, capture_output=True, cwd=ROOT)
            for i in range(items):
                rm.http('POST', f'/api/v1/migration/shops/{shop}/inventory/{rm.FIRST_ITEM_ID + i}/locations/'
                                f'{rm.LOCATION_ID}/legacy-seed?available={ON_HAND}', headers=token)
            rm.http('PUT', f'/api/v1/migration/shops/{shop}/mode', {'mode': 'REDIS_PRIMARY'}, headers=token)

        def mismatches_for(shop):
            total = 0
            for port in rm.PORTS:
                _, body = rm.http('GET', '/api/v1/migration/mismatches', headers=token, port=port)
                total += sum(1 for m in json.loads(body) if m['shopId'] == shop)
            return total

        k6 = k6_process('migration.js', env, dict(VUS=50, DURATION=f'{args.seconds}s', TTL_SECONDS=ttl,
                                                  ITEM_COUNT=items,
                                                  SHOPS=','.join(f'{s}:{w}' for s, w in shops)),
                        scratch / 'rollout.json')
        started = time.time()
        snapshot = rm.prometheus()
        halted = None
        for index, (shop, weight) in enumerate(shops):
            # 每个店铺先在影子阶段观察一个窗口；第一个店铺之前还有一个全体影子窗口。
            time.sleep(max(0.0, started + (index + 2) * window - time.time()))
            observed = mismatches_for(shop)
            gates.append(dict(shop=shop, weight=weight, at_s=round(time.time() - started, 1),
                              shadow_mismatches=observed, promoted=observed == 0 and halted is None))
            if observed or halted:
                halted = halted or shop
                log(f'rollout halted at shop {shop}: {observed} mismatches')
                continue
            rm.http('PUT', f'/api/v1/migration/shops/{shop}/mode', {'mode': 'MYSQL_PRIMARY'}, headers=token)
            now = rm.prometheus()
            timeline.append(dict(after_s=round(time.time() - started, 1), promoted_shop=shop,
                                 metrics_since_previous=rm.delta(now, snapshot)))
            snapshot = now
            log(f'shop {shop} (weight {weight}) -> MYSQL_PRIMARY at t={time.time() - started:.1f}s')
        k6_output = finish(k6)
        stopped = time.time()
        log(f'k6 finished (exit {k6.returncode}); polling until every shop converges')
        item_list = ','.join(str(rm.FIRST_ITEM_ID + i) for i in range(items))
        final = {}
        while True:
            final = {}
            for shop, _ in shops:
                _, body = rm.http('GET', f'/api/v1/migration/shops/{shop}/consistency?items={item_list}'
                                         f'&locationId={rm.LOCATION_ID}', headers=token)
                final[shop] = json.loads(body)
            if all(c['consistent'] for c in final.values()) or time.time() - stopped > 180:
                break
            time.sleep(5)
        drift = {}
        for shop, _ in shops:
            _, body = rm.http('GET', f'/api/v1/migration/shops/{shop}/legacy-drift?items={item_list}'
                                     f'&locationId={rm.LOCATION_ID}', headers=token)
            drift[shop] = sum(abs(d['ledgerOnHand'] - d['redisAvailable'] - d['redisReserved'])
                              for d in json.loads(body))
        modes = {shop: json.loads(rm.http('GET', f'/api/v1/migration/shops/{shop}/mode')[1])['mode']
                 for shop, _ in shops}
        audits = {}
        for shop, _ in shops:
            rows = []
            for i in range(items):
                _, body = rm.http('POST', '/api/v1/admin/invariants/check',
                                  {'shopId': shop, 'inventoryItemId': rm.FIRST_ITEM_ID + i,
                                   'locationId': rm.LOCATION_ID}, headers=token)
                rows.append(json.loads(body)['valid'])
            audits[shop] = all(rows)
        return dict(experiment='rollout', seconds=args.seconds, shops=shops, gates=gates, halted_at=halted,
                    final_modes=modes, timeline=timeline, k6_exit=k6.returncode,
                    k6=counters(scratch / 'rollout.json'),
                    converged_after_s=round(time.time() - stopped, 1),
                    consistent={shop: c['consistent'] for shop, c in final.items()},
                    status_counts={shop: c['mysqlStatusCounts'] for shop, c in final.items()},
                    legacy_drift_abs=drift, mysql_audit_valid=audits, k6_log_tail=k6_output[-3000:])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('drill', choices=['crash', 'proxysql', 'proxysql-exhaustion', 'migration', 'rollout'])
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--seconds', type=int, default=90, help='crash / migration 的负载时长')
    parser.add_argument('--reps', type=int, default=3, help='proxysql 每个配置的重复次数')
    parser.add_argument('--warmup', default='10s')
    parser.add_argument('--duration', default='60s', help='proxysql 每次测量窗口')
    args = parser.parse_args()
    rm.keep_awake()
    env = rm.load_env()
    (args.out / 'scratch').mkdir(parents=True, exist_ok=True)
    result = {'crash': crash, 'proxysql': proxysql, 'proxysql-exhaustion': proxysql_exhaustion,
              'migration': migration, 'rollout': rollout}[args.drill](args, env)
    if not args.drill.startswith('proxysql'):
        (args.out / f'{args.drill}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k not in ('k6_log_tail', 'mismatch_samples', 'phases',
                                                                  'app_metrics_delta')},
                     ensure_ascii=False, indent=2)[:4000])


if __name__ == '__main__':
    sys.exit(main())
