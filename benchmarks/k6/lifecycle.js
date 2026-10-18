// 完整生命周期负载：70% 预留后 claim、20% 预留后 cancel、10% 等待到期释放。
// 等待到期的那部分建议配合较短 TTL（仍需 >= 5 秒），并让过期清理有足够时间执行。
//
//   k6 run -e VUS=50 -e MODE=pool -e ITEM_COUNT=10 -e TTL_SECONDS=10 benchmarks/k6/lifecycle.js
import { check } from 'k6';
import { cancel, claim, OP_TRENDS, reserve, stages } from './lib.js';

// 默认 70% 确认、20% 取消、其余等待到期；E17 用更高的到期比例考验到期清理。
const CLAIM_SHARE = Number(__ENV.CLAIM_SHARE || 0.7);
const CANCEL_SHARE = Number(__ENV.CANCEL_SHARE || 0.2);

export const options = {
  stages: stages(),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    ...OP_TRENDS,
    reserve_other_errors: ['count==0'],
    checks: ['rate==1'],
  },
};

export default function () {
  const reservationId = reserve();
  if (!reservationId) {
    return;
  }
  const dice = Math.random();
  if (dice < CLAIM_SHARE) {
    // 预留已经持有额度：claim 不应因为“库存被抢光”而失败。
    check(claim(reservationId), { 'claim succeeded': (r) => r.status === 200 });
  } else if (dice < CLAIM_SHARE + CANCEL_SHARE) {
    check(cancel(reservationId), { 'cancel succeeded': (r) => r.status === 200 });
  }
  // 其余 10% 留给后台到期任务释放。
}
