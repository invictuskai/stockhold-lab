// 共享配置与请求封装。所有参数通过环境变量传入：k6 run -e VUS=50 -e MODE=pool ...
import { sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
// 多应用实例：逗号分隔的地址列表。每个 VU 固定发往一个实例，确认 / 取消发往下一个实例，
// 以便验证预留状态在实例之间一致（状态只在 MySQL 中）。
const BASE_URLS = (__ENV.BASE_URLS || BASE_URL).split(',').map((url) => url.trim()).filter((url) => url);
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

const RESERVATIONS_PATH = MODE === 'counter'
  ? `/api/v1/benchmark/counter/shops/${SHOP_ID}/reservations`
  : `/api/v1/shops/${SHOP_ID}/reservations`;

/** 当前 VU 的预留地址；offset = 1 表示列表中的下一个实例。 */
function reservations(offset = 0) {
  return BASE_URLS[(__VU + offset) % BASE_URLS.length] + RESERVATIONS_PATH;
}

// 成功与各类失败分开计数：库存耗尽后快速返回的 409 不能被算作吞吐提升。
export const created = new Counter('reserve_created');
export const insufficient = new Counter('reserve_insufficient_stock');
export const busy = new Counter('reserve_busy_503');
export const throttled = new Counter('reserve_throttled_429');
export const otherErrors = new Counter('reserve_other_errors');

export function uniqueKey(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

// 商品分布：uniform（默认）或 zipf（排名为 r 的商品权重 1 / r^ZIPF_S，少数商品承接大部分流量）。
const DISTRIBUTION = __ENV.DISTRIBUTION || 'uniform';
const ZIPF_S = Number(__ENV.ZIPF_S || 1.0);
const ZIPF_CUMULATIVE = (() => {
  const weights = [];
  for (let r = 1; r <= ITEM_COUNT; r++) {
    weights.push(1 / Math.pow(r, ZIPF_S));
  }
  const total = weights.reduce((a, b) => a + b, 0);
  let running = 0;
  return weights.map((w) => (running += w / total));
})();

function firstItemIndex() {
  if (DISTRIBUTION !== 'zipf') {
    return Math.floor(Math.random() * ITEM_COUNT);
  }
  const roll = Math.random();
  const index = ZIPF_CUMULATIVE.findIndex((c) => roll <= c);
  return index < 0 ? ITEM_COUNT - 1 : index;
}

export function cartLines() {
  const start = firstItemIndex();
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
  const response = http.post(reservations(), JSON.stringify({ ttlSeconds: TTL_SECONDS, lines: cartLines() }), {
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
  return http.post(`${reservations(1)}/${reservationId}/claim`,
    JSON.stringify({ paymentReference: uniqueKey('pay') }),
    { headers: { 'Content-Type': 'application/json' }, tags: { op: 'claim' } });
}

export function cancel(reservationId) {
  return http.post(`${reservations(1)}/${reservationId}/cancel`, null, { tags: { op: 'cancel' } });
}

// 恒为真的子指标阈值：让 k6 汇总按操作分别输出延迟分布，而不是只有混合的 http_req_duration。
export const OP_TRENDS = {
  'http_req_duration{op:reserve}': ['max>=0'],
  'http_req_duration{op:claim}': ['max>=0'],
  'http_req_duration{op:cancel}': ['max>=0'],
  'http_reqs{op:reserve}': ['count>=0'],
};

// 预热 30 秒 + 测量 120 秒（实现与验证计划 6.4）；设备资源不足时降级并在报告中记录实际时长。
export function stages() {
  const vus = Number(__ENV.VUS || 10);
  return [
    { duration: __ENV.WARMUP || '30s', target: vus },
    { duration: __ENV.DURATION || '120s', target: vus },
  ];
}

// ---------------------------------------------------------------- 故障与迁移演练共用

/** 所有实例地址（BASE_URLS），供需要在实例间切换的脚本使用。 */
export function instanceUrls() {
  return BASE_URLS;
}

/**
 * 网络错误或 5xx 时，用同一个请求体与请求头（同一个幂等键 / 支付引用）依次换到下一个实例重试。
 * 这正是“提交成功但响应丢失”的恢复方式：重试会得到幂等重放，而不是第二个预留。
 * 返回 { response, attempts }；response 为 null 表示所有尝试都失败。
 */
export function postWithRetry(pathFor, body, params, maxAttempts = 8, offset = 0) {
  // trace 记录每次尝试的目标、状态、开始时间与耗时，供故障分析使用。
  const trace = [];
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const base = BASE_URLS[(__VU + offset + attempt) % BASE_URLS.length];
    const startedAt = Date.now();
    const response = http.post(base + pathFor, body, Object.assign({ timeout: '5s' }, params));
    trace.push({ port: base.split(':').pop(), status: response.status, at: startedAt, ms: Date.now() - startedAt });
    if (response.status !== 0 && response.status < 500) {
      return { response, attempts: attempt + 1, trace };
    }
    sleep(0.2);
  }
  return { response: null, attempts: maxAttempts, trace };
}
