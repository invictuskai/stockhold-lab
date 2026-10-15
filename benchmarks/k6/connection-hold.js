// 连接瓶颈实验（实现与验证计划 6.3）：在 reserve 负载旁边注入“获取连接后持有但不执行 SQL”的调用，
// 并与等长的“事务外等待”对照。需要 dev,benchmark profile 与管理令牌。
//
//   k6 run -e VUS=50 -e HOLD_VUS=16 -e HOLD_MODE=connection -e ADMIN_TOKEN=... benchmarks/k6/connection-hold.js
//   k6 run -e VUS=50 -e HOLD_VUS=16 -e HOLD_MODE=outside    -e ADMIN_TOKEN=... benchmarks/k6/connection-hold.js
//
// 预期：connection 模式下 stockhold.connection.hold{op=benchmark} 与 hikaricp.connections.pending 上升，
// reserve 的连接获取等待变长但其 SQL 时间不变；outside 模式不占用连接。
import http from 'k6/http';
import { BASE_URL, reserve } from './lib.js';

const DURATION = __ENV.DURATION || '120s';

export const options = {
  scenarios: {
    reserve: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: DURATION, exec: 'reserveLoad' },
    hold: { executor: 'constant-vus', vus: Number(__ENV.HOLD_VUS || 8), duration: DURATION, exec: 'holdLoad' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function reserveLoad() {
  reserve();
}

export function holdLoad() {
  const mode = __ENV.HOLD_MODE || 'connection';
  const millis = __ENV.HOLD_MILLIS || '100';
  http.post(`${BASE_URL}/api/v1/benchmark/hold?mode=${mode}&millis=${millis}`, null, {
    headers: { 'X-Admin-Token': __ENV.ADMIN_TOKEN || '' },
    tags: { op: `hold_${mode}` },
  });
}
