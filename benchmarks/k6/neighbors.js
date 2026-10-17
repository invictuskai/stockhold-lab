// “安全的邻居”实验：与预留共用数据库的其他业务（购物车更新 + 下单）以固定到达率运行，
// 可选地叠加单位池或单计数器的热点预留负载，观察邻居的延迟与成功率是否被拖累。需要 benchmark profile。
//
//   k6 run -e NEIGHBOR_RATE=300 -e RESERVE_VUS=0   neighbors.js   # 只有邻居
//   k6 run -e NEIGHBOR_RATE=300 -e RESERVE_VUS=100 -e MODE=counter neighbors.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { BASE_URL, OP_TRENDS, reserve } from './lib.js';

const DURATION = __ENV.DURATION || '60s';
const RESERVE_VUS = Number(__ENV.RESERVE_VUS || 0);
const neighborOk = new Counter('neighbor_ok');
const neighborFailed = new Counter('neighbor_failed');

const scenarios = {
  neighbors: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.NEIGHBOR_RATE || 300), timeUnit: '1s',
    duration: DURATION, preAllocatedVUs: 50, maxVUs: 400, exec: 'neighbor',
  },
};
if (RESERVE_VUS > 0) {
  scenarios.reserve = { executor: 'constant-vus', vus: RESERVE_VUS, duration: DURATION, exec: 'reserveLoad' };
}

export const options = {
  scenarios,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { ...OP_TRENDS, 'http_req_duration{op:neighbor}': ['max>=0'] },
};

export function neighbor() {
  // NEIGHBOR_BASE_URL 可指向另一个应用进程，使邻居与预留不共用应用连接池（E15b）。
  const response = http.post(`${__ENV.NEIGHBOR_BASE_URL || BASE_URL}/api/v1/benchmark/neighbor`, null,
    { tags: { op: 'neighbor' }, timeout: '10s' });
  (response.status === 200 ? neighborOk : neighborFailed).add(1);
}

export function reserveLoad() {
  reserve();
}
