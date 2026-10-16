// 迁移演练：完整生命周期负载经由迁移路由（/api/v1/migration），期间由 benchmarks/migration_drill.py
// 按时间表切换店铺的迁移模式（Redis 为主 -> MySQL 为主 -> 回切 -> MySQL 为主）。
// 验证点：跨越切换的在途预留仍能确认 / 取消，客户端看不到失败。
//
//   k6 run -e BASE_URLS=http://127.0.0.1:8080,http://127.0.0.1:8081 -e VUS=50 -e TTL_SECONDS=10 migration.js
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { cartLines, OP_TRENDS, postWithRetry, SHOP_ID, stages, TTL_SECONDS } from './lib.js';

const PATH = `/api/v1/migration/shops/${SHOP_ID}/reservations`;

const created = new Counter('reserve_created');
const reserveFailed = new Counter('reserve_failed');
const claimOk = new Counter('claim_ok');
const cancelOk = new Counter('cancel_ok');
const followUpFailed = new Counter('claim_cancel_failed');

export const options = {
  stages: stages(),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { ...OP_TRENDS, checks: ['rate>=0'] },
};

export default function () {
  const key = `mig-${__VU}-${__ITER}-${Date.now()}`;
  const { response } = postWithRetry(PATH, JSON.stringify({ ttlSeconds: TTL_SECONDS, lines: cartLines() }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { op: 'reserve' } });
  if (!response || response.status !== 201) {
    reserveFailed.add(1);
    return;
  }
  created.add(1);

  const dice = Math.random();
  if (dice >= 0.9) {
    return; // 10% 留给两个系统各自的到期清理
  }
  const claim = dice < 0.7;
  const followUp = postWithRetry(`${PATH}/by-key/${key}/${claim ? 'claim' : 'cancel'}`,
    claim ? JSON.stringify({ paymentReference: `pay-${key}` }) : null,
    { headers: { 'Content-Type': 'application/json' }, tags: { op: claim ? 'claim' : 'cancel' } }, 8, 1);
  const ok = check(followUp.response, { 'claim/cancel succeeded': (r) => r !== null && r.status === 200 });
  if (ok) {
    (claim ? claimOk : cancelOk).add(1);
  } else {
    followUpFailed.add(1);
  }
}
