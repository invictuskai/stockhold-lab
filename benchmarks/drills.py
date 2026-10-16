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
    """测量窗口内：重置并读取代理侧的查询摘要；约每 100 ms 采样代理的会话表，估算各标签占用后端连接的时间。"""

    def __init__(self, admin, seconds):
        self.admin = admin
        self.seconds = seconds
        self.process = None
        self.lines = []
        self.reader = None

    def start(self):
        self.admin.query('SELECT COUNT(*) FROM stats_mysql_query_digest_reset')
        loop = (f'end=$(( $(date +%s) + {self.seconds} )); while [ $(date +%s) -lt $end ]; do '
                'mysql -h proxysql -P6032 -uradmin -N -B -e "'
                "SELECT 'S', SessionID, IFNULL(srv_host, ''), command, IFNULL(info, '') FROM stats_mysql_processlist;"
                "SELECT 'P', SUM(ConnUsed), SUM(ConnFree) FROM stats_mysql_connection_pool;"
                '"; echo "#"; sleep 0.1; done')
        self.process = subprocess.Popen(
            rm.compose_cmd() + ['exec', '-T', '-e', f'MYSQL_PWD={self.admin.password}', 'mysql', 'sh', '-c', loop],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, cwd=ROOT)
        self.reader = threading.Thread(target=lambda: self.lines.extend(self.process.stdout), daemon=True)
        self.reader.start()

    def stop(self):
        self.process.wait(30)
        self.reader.join(10)
        digests = {}
        for text, count, sum_time in self.admin.query(
                'SELECT digest_text, count_star, sum_time FROM stats_mysql_query_digest'):
            tag = tag_of(text) or 'untagged'
            entry = digests.setdefault(tag, dict(statements=0, sql_seconds=0.0))
            entry['statements'] += int(count)
            entry['sql_seconds'] += int(sum_time) / 1e6
        samples, backend, last_tag, used = 0, {}, {}, []
        for line in self.lines:
            line = line.rstrip('\n')
            if line == '#':
                samples += 1
                continue
            parts = line.split('\t')
            if parts[0] == 'P' and len(parts) >= 2 and parts[1] not in ('', 'NULL'):
                used.append(int(parts[1]))
            elif parts[0] == 'S' and len(parts) >= 5:
                session, server, info = parts[1], parts[2], parts[4]
                tag = tag_of(info)
                if tag:
                    last_tag[session] = tag
                if server:
                    # 持有后端连接：正在执行的语句带标签；事务内空闲时归给该会话最近一次看到的标签。
                    key = tag or last_tag.get(session, 'unknown')
                    backend[key] = backend.get(key, 0) + 1
        interval = self.seconds / max(samples, 1)
        return dict(proxy_digest_by_tag=digests, proxy_samples=samples, proxy_sample_interval_s=round(interval, 3),
                    proxy_backend_seconds_per_second_by_tag={k: round(v * interval / self.seconds, 3)
                                                             for k, v in sorted(backend.items())},
                    proxy_backend_conn_used=dict(avg=round(sum(used) / len(used), 2) if used else None,
                                                 max=max(used) if used else None))


def proxysql(args, env):
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
    configs = [
        dict(name='proxy-reserve-vus50', script='reserve-only.js', mode='pool', items=10, capacity=1000,
             k6=dict(VUS=50, ITEM_COUNT=10, CART_DIMENSIONS=1, QUANTITY=1)),
        dict(name='proxy-hold-outside', script='connection-hold.js', mode='pool', items=10, capacity=1000,
             k6=dict(VUS=50, HOLD_VUS=16, HOLD_MODE='outside', HOLD_MILLIS=100, ITEM_COUNT=10)),
        dict(name='proxy-hold-connection', script='connection-hold.js', mode='pool', items=10, capacity=1000,
             k6=dict(VUS=50, HOLD_VUS=16, HOLD_MODE='connection', HOLD_MILLIS=100, ITEM_COUNT=10)),
    ]
    group = dict(id='E10', title='经 ProxySQL：应用侧与代理侧的连接归因', app_env={'STOCKHOLD_DB_URL': url})
    seconds = int(args.duration.rstrip('s'))
    results = []
    with rm.App(env, group['app_env'], args.out / 'scratch', 'proxysql'):
        for config in configs:
            for rep in range(1, args.reps + 1):
                log(f"E10 {config['name']} rep {rep}")
                result = rm.run_once(group, config, rep, env, args, args.out, ON_HAND,
                                     hooks=ProxyHooks(admin, seconds))
                result['proxysql_version'] = admin.query(
                    "SELECT variable_value FROM global_variables WHERE variable_name = 'admin-version'")[0][0]
                (args.out / f"E10-{config['name']}-r{rep}.json").write_text(
                    json.dumps(result, ensure_ascii=False, indent=2))
                results.append(result)
    return dict(experiment='proxysql', runs=len(results))


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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('drill', choices=['crash', 'proxysql', 'migration'])
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--seconds', type=int, default=90, help='crash / migration 的负载时长')
    parser.add_argument('--reps', type=int, default=3, help='proxysql 每个配置的重复次数')
    parser.add_argument('--warmup', default='10s')
    parser.add_argument('--duration', default='60s', help='proxysql 每次测量窗口')
    args = parser.parse_args()
    env = rm.load_env()
    (args.out / 'scratch').mkdir(parents=True, exist_ok=True)
    result = {'crash': crash, 'proxysql': proxysql, 'migration': migration}[args.drill](args, env)
    if args.drill != 'proxysql':
        (args.out / f'{args.drill}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k not in ('k6_log_tail', 'mismatch_samples', 'phases',
                                                                  'app_metrics_delta')},
                     ensure_ascii=False, indent=2)[:4000])


if __name__ == '__main__':
    sys.exit(main())
