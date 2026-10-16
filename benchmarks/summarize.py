#!/usr/bin/env python3
"""把 run_matrix.py 的原始 JSON 汇总成 Markdown 表格（每个配置取各次重复的中位数，并给出范围）。

    python3 benchmarks/summarize.py benchmarks/reports/raw/2026-10-15 > /tmp/tables.md
"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def load(directory):
    runs = defaultdict(list)
    for path in sorted(Path(directory).glob('E*-r*.json')):
        run = json.loads(path.read_text())
        runs[(run['group'], run['config'])].append(run)
    return runs


def metric(run, *path, default=0.0):
    value = run
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return default
        value = value[key]
    return value


def app(run, name):
    return run['app_metrics_delta'].get(name, 0.0)


def per_second(run, value):
    seconds = float(run['duration'].rstrip('s'))
    return value / seconds


def fmt(values, digits=0, suffix=''):
    """中位数（最小–最大）。"""
    values = [v for v in values if v is not None]
    if not values:
        return '—'
    median = statistics.median(values)
    if len(values) == 1 or max(values) == min(values):
        return f'{median:,.{digits}f}{suffix}'
    return f'{median:,.{digits}f}{suffix} ({min(values):,.{digits}f}–{max(values):,.{digits}f})'


def reserve_row(runs):
    created = [metric(r, 'k6', 'reserve_created', 'rate') for r in runs]
    attempted = [metric(r, 'k6', 'http_reqs{op:reserve}', 'rate') for r in runs]
    busy = [metric(r, 'k6', 'reserve_busy_503', 'count') for r in runs]
    throttled = [metric(r, 'k6', 'reserve_throttled_429', 'count') for r in runs]
    insufficient = [metric(r, 'k6', 'reserve_insufficient_stock', 'count') for r in runs]
    other = [metric(r, 'k6', 'reserve_other_errors', 'count') for r in runs]
    lat = {q: [metric(r, 'k6', 'http_req_duration{op:reserve}', q) for r in runs] for q in ('med', 'p(95)', 'p(99)')}
    lock_waits = [r['mysql_delta'].get('Innodb_row_lock_waits', 0.0) for r in runs]
    lock_time = [r['mysql_delta'].get('Innodb_row_lock_time', 0.0) for r in runs]
    reservations = [max(metric(r, 'k6', 'reserve_created', 'count'), 1) for r in runs]
    return dict(
        created=fmt(created), attempted=fmt(attempted),
        p50=fmt(lat['med'], 1), p95=fmt(lat['p(95)'], 1), p99=fmt(lat['p(99)'], 1),
        failures=f"{int(sum(busy))} / {int(sum(throttled))} / {int(sum(insufficient))} / {int(sum(other))}",
        lock_waits_per_res=fmt([w / n for w, n in zip(lock_waits, reservations)], 3),
        lock_wait_ms=fmt([t / w if w else 0 for t, w in zip(lock_time, lock_waits)], 1),
        deadlocks=int(sum(r['mysql_delta'].get('lock_deadlocks', 0) for r in runs)),
        lock_timeouts=int(sum(r['mysql_delta'].get('lock_timeouts', 0) for r in runs)),
        threads_running=fmt([metric(r, 'samples', 'threads_running', 'avg', default=None) for r in runs], 1),
        mysql_cpu=fmt([metric(r, 'samples', 'mysql_container_cpu_percent', 'avg', default=None) for r in runs], 0,
                      '%'),
        pending=fmt([metric(r, 'samples', 'hikari_pending', 'avg', default=None) for r in runs], 1),
        pool_miss=fmt([per_second(r, app(r, 'stockhold_reserve_pool_miss_total')) for r in runs], 1),
        sync_refills=fmt([per_second(r, app(r, 'stockhold_refill_units_count{trigger="sync"}')) for r in runs], 1),
        refill_units=fmt([app(r, 'stockhold_refill_units_sum{trigger="sync"}')
                          / max(app(r, 'stockhold_refill_units_count{trigger="sync"}'), 1) for r in runs], 0),
        retries=fmt([per_second(r, sum(v for k, v in r['app_metrics_delta'].items()
                                       if k.startswith('stockhold_reserve_retries_total'))) for r in runs], 1),
        audit='全部通过' if all(r['audit']['valid'] for r in runs) else '**失败**',
        reps=len(runs))


def connection_row(runs, op):
    hold = [app(r, f'stockhold_connection_hold_seconds_sum{{op="{op}"}}') * 1000
            / max(app(r, f'stockhold_connection_hold_seconds_count{{op="{op}"}}'), 1) for r in runs]
    acquire = [app(r, f'stockhold_connection_acquire_seconds_sum{{op="{op}"}}') * 1000
               / max(app(r, f'stockhold_connection_acquire_seconds_count{{op="{op}"}}'), 1) for r in runs]
    sql = [app(r, f'stockhold_sql_seconds_sum{{op="{op}"}}') * 1000
           / max(app(r, f'stockhold_sql_seconds_count{{op="{op}"}}'), 1) for r in runs]
    total_hold = [per_second(r, app(r, f'stockhold_connection_hold_seconds_sum{{op="{op}"}}')) for r in runs]
    return dict(hold=fmt(hold, 2), acquire=fmt(acquire, 2), sql=fmt(sql, 2), hold_per_s=fmt(total_hold, 2))


def table(headers, rows):
    lines = ['| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join('---' for _ in headers) + ' |']
    lines += ['| ' + ' | '.join(str(c) for c in row) + ' |' for row in rows]
    return '\n'.join(lines)


def main():
    runs = load(sys.argv[1])
    out = []
    groups = defaultdict(list)
    for (group, config), items in runs.items():
        groups[group].append((config, items))

    for group in sorted(groups):
        configs = groups[group]
        out.append(f"## {group}：{configs[0][1][0]['title']}\n")
        rows, detail, conn = [], [], []
        for config, items in configs:
            r = reserve_row(items)
            rows.append([config, r['created'], r['attempted'], r['p50'], r['p95'], r['p99'], r['failures'],
                         r['audit']])
            detail.append([config, r['lock_waits_per_res'], r['lock_wait_ms'], r['deadlocks'], r['lock_timeouts'],
                           r['threads_running'], r['mysql_cpu'], r['pending'], r['pool_miss'], r['sync_refills'],
                           r['refill_units'], r['retries']])
            op = 'benchmark' if items[0]['mode'] == 'counter' else 'reserve'
            c = connection_row(items, op)
            conn.append([config, op, c['acquire'], c['hold'], c['sql'], c['hold_per_s']])
        out.append(table(['配置', '成功预留/s', '尝试/s', 'P50 ms', 'P95 ms', 'P99 ms',
                          '503 / 429 / 409 / 其他', '审计'], rows))
        out.append('')
        out.append(table(['配置', '行锁等待/每次成功预留', '平均行锁等待 ms', '死锁', '锁超时', 'Threads_running',
                          'MySQL CPU', 'Hikari pending', '池缺行/s', '同步补充/s', '每次补充单位', 'reserve 重试/s'],
                         detail))
        out.append('')
        out.append(table(['配置', '归因标签', '连接获取 ms', '连接持有 ms', 'SQL ms', '每秒连接占用 s'], conn))
        if any(items[0]['script'] == 'lifecycle.js' for _, items in configs):
            life = []
            for config, items in configs:
                life.append([config,
                             fmt([metric(r, 'k6', 'http_req_duration{op:claim}', 'p(99)') for r in items], 1),
                             fmt([metric(r, 'k6', 'http_req_duration{op:cancel}', 'p(99)') for r in items], 1),
                             fmt([metric(r, 'k6', 'checks', 'value') * 100 for r in items], 2, '%')])
            out.append('')
            out.append(table(['配置', 'claim P99 ms', 'cancel P99 ms', 'claim/cancel 成功率'], life))
        if any(items[0].get('extra', {}).get('proxy_backend_seconds_per_second_by_tag') for _, items in configs):
            proxy = []
            for config, items in configs:
                def backend(tag):
                    return [r['extra']['proxy_backend_seconds_per_second_by_tag'].get(tag, 0.0) for r in items]

                def app_hold(tag):
                    return [per_second(r, app(r, f'stockhold_connection_hold_seconds_sum{{op="{tag}"}}'))
                            for r in items]
                proxy.append([config, fmt(app_hold('reserve'), 2), fmt(backend('reserve'), 2),
                              fmt(app_hold('benchmark'), 2), fmt(backend('benchmark'), 2),
                              fmt([r['extra']['proxy_backend_conn_used']['avg'] for r in items], 1),
                              fmt([r['extra']['proxy_backend_conn_used']['max'] for r in items], 0)])
            out.append('')
            out.append(table(['配置', 'reserve 应用侧连接占用 s/s', 'reserve 代理后端占用 s/s',
                              '注入调用 应用侧 s/s', '注入调用 代理后端 s/s', '后端连接使用 平均', '后端连接使用 峰值'],
                             proxy))
        if any(items[0]['script'] == 'connection-hold.js' for _, items in configs):
            hold = []
            for config, items in configs:
                c = connection_row(items, 'benchmark')
                hold.append([config, c['hold'], c['hold_per_s'],
                             fmt([metric(r, 'samples', 'hikari_pending', 'max', default=None) for r in items], 0)])
            out.append('')
            out.append(table(['配置', '注入调用的连接持有 ms', '注入调用每秒连接占用 s', 'Hikari pending 峰值'], hold))
        out.append('')
    print('\n'.join(out))


if __name__ == '__main__':
    main()
