// 共享配置与请求封装。所有参数通过环境变量传入：k6 run -e VUS=50 -e MODE=pool ...
import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
export const SHOP_ID = Number(__ENV.SHOP_ID || 1001);
export const LOCATION_ID = Number(__ENV.LOCATION_ID || 3001);
export const FIRST_ITEM_ID = Number(__ENV.FIRST_ITEM_ID || 2001);
// 商品分布：1 = 单一热点；10 = 10 热点；1000 = 均匀。
export const ITEM_COUNT = Number(__ENV.ITEM_COUNT || 1);
// 每个购物车的维度数（1、5、20）与单维度数量（1、5、50，不超过池容量）。
export const CART_DIMENSIONS = Number(__ENV.CART_DIMENSIONS || 1);
export const QUANTITY = Number(__ENV.QUANTITY || 1);
// pool = 单位池方案；counter = 单计数器对照组（需要 benchmark profile）。
export const MODE = __ENV.MODE || 'pool';
export const TTL_SECONDS = Number(__ENV.TTL_SECONDS || 300);

const RESERVATIONS = MODE === 'counter'
  ? `${BASE_URL}/api/v1/benchmark/counter/shops/${SHOP_ID}/reservations`
  : `${BASE_URL}/api/v1/shops/${SHOP_ID}/reservations`;

// 成功与各类失败分开计数：库存耗尽后快速返回的 409 不能被算作吞吐提升。
export const created = new Counter('reserve_created');
export const insufficient = new Counter('reserve_insufficient_stock');
export const busy = new Counter('reserve_busy_503');
export const throttled = new Counter('reserve_throttled_429');
export const otherErrors = new Counter('reserve_other_errors');

export function uniqueKey(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

export function cartLines() {
  const start = Math.floor(Math.random() * ITEM_COUNT);
  const lines = [];
  for (let i = 0; i < Math.min(CART_DIMENSIONS, ITEM_COUNT); i++) {
    lines.push({
      inventoryItemId: FIRST_ITEM_ID + ((start + i) % ITEM_COUNT),
      locationId: LOCATION_ID,
      quantity: QUANTITY,
    });
  }
  return lines;
}

export function reserve() {
  const response = http.post(RESERVATIONS, JSON.stringify({ ttlSeconds: TTL_SECONDS, lines: cartLines() }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uniqueKey('k6') },
    tags: { op: 'reserve' },
    responseCallback: http.expectedStatuses(200, 201, 409),
  });
  if (response.status === 201) {
    created.add(1);
    return response.json('reservationId');
  }
  if (response.status === 409) {
    insufficient.add(1);
  } else if (response.status === 503) {
    busy.add(1);
  } else if (response.status === 429) {
    throttled.add(1);
  } else {
    otherErrors.add(1);
  }
  return null;
}

export function claim(reservationId) {
  return http.post(`${RESERVATIONS}/${reservationId}/claim`,
    JSON.stringify({ paymentReference: uniqueKey('pay') }),
    { headers: { 'Content-Type': 'application/json' }, tags: { op: 'claim' } });
}

export function cancel(reservationId) {
  return http.post(`${RESERVATIONS}/${reservationId}/cancel`, null, { tags: { op: 'cancel' } });
}

// 预热 30 秒 + 测量 120 秒（实现与验证计划 6.4）；设备资源不足时降级并在报告中记录实际时长。
export function stages() {
  const vus = Number(__ENV.VUS || 10);
  return [
    { duration: __ENV.WARMUP || '30s', target: vus },
    { duration: __ENV.DURATION || '120s', target: vus },
  ];
}
