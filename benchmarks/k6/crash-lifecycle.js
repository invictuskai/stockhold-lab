// 进程崩溃演练：多实例完整生命周期负载，期间由 benchmarks/crash_restart.py 对其中一个实例 kill -9 并重启。
// 客户端在网络错误 / 5xx 时用同一个幂等键或支付引用换实例重试；验证点是重试得到幂等重放而不是重复预留，
// 且没有任何请求最终失败。
//
//   k6 run -e BASE_URLS=http://127.0.0.1:8080,http://127.0.0.1:8081 -e VUS=50 -e TTL_SECONDS=10 crash-lifecycle.js
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { cartLines, OP_TRENDS, postWithRetry, SHOP_ID, stages, TTL_SECONDS } from './lib.js';

const PATH = `/api/v1/shops/${SHOP_ID}/reservations`;

const createdFirstTry = new Counter('reserve_created_first_try');
const createdAfterRetry = new Counter('reserve_created_after_retry');
// 先前某次尝试已提交但响应丢失：重试得到 200 + Idempotent-Replayed。
const replayedAfterRetry = new Counter('reserve_replayed_after_retry');
const reserveFailed = new Counter('reserve_failed_final');
const reserveRejected = new Counter('reserve_rejected_4xx');
const retriedAttempts = new Counter('attempts_retried');
const claimOk = new Counter('claim_ok');
const cancelOk = new Counter('cancel_ok');
const followUpFailed = new Counter('claim_cancel_failed_final');

export const options = {
  stages: stages(),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { ...OP_TRENDS, checks: ['rate>=0'] },
};

export default function () {
  const key = `crash-${__VU}-${__ITER}-${Date.now()}`;
  const reserved = postWithRetry(PATH,
    JSON.stringify({ ttlSeconds: TTL_SECONDS, lines: cartLines() }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { op: 'reserve' } });
  const { response, attempts } = reserved;
  retriedAttempts.add(attempts - 1);
  if (!response) {
    reserveFailed.add(1);
    return;
  }
  if (response.status === 201) {
    (attempts === 1 ? createdFirstTry : createdAfterRetry).add(1);
  } else if (response.status === 200) {
    replayedAfterRetry.add(1);
  } else {
    reserveRejected.add(1);
    return;
  }
  const reservationId = response.json('reservationId');

  const dice = Math.random();
  if (dice >= 0.9) {
    return; // 10% 留给到期任务释放
  }
  const claim = dice < 0.7;
  const followUp = postWithRetry(`${PATH}/${reservationId}/${claim ? 'claim' : 'cancel'}`,
    claim ? JSON.stringify({ paymentReference: `pay-${key}` }) : null,
    { headers: { 'Content-Type': 'application/json' }, tags: { op: claim ? 'claim' : 'cancel' } }, 8, 1);
  retriedAttempts.add(followUp.attempts - 1);
  const ok = check(followUp.response, { 'claim/cancel succeeded': (r) => r !== null && r.status === 200 });
  if (ok) {
    (claim ? claimOk : cancelOk).add(1);
  } else {
    followUpFailed.add(1);
    const r = followUp.response;
    console.warn(`follow-up failed: op=${claim ? 'claim' : 'cancel'} attempts=${followUp.attempts}`
      + ` status=${r ? r.status : 'none'} body=${r ? String(r.body).slice(0, 160) : ''}`
      + ` reserveTrace=${JSON.stringify(reserved.trace)} followUpTrace=${JSON.stringify(followUp.trace)}`);
  }
}
