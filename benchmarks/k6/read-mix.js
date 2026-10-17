// 读写混合：每次迭代一次预留 + READS 次库存快照查询。CONSISTENCY=eventual 时查询走只读副本（应用需配置
// STOCKHOLD_REPLICA_DB_URL），strong 时走主库；用于观察把读流量移到副本后主库负载与预留吞吐的变化。
//
//   k6 run -e VUS=50 -e READS=4 -e CONSISTENCY=eventual read-mix.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { BASE_URL, cartLines, OP_TRENDS, reserve, SHOP_ID, stages } from './lib.js';

const READS = Number(__ENV.READS || 4);
const CONSISTENCY = __ENV.CONSISTENCY || 'strong';
const readOk = new Counter('read_ok');
const readFailed = new Counter('read_failed');
const readFromReplica = new Counter('read_from_replica');

export const options = {
  stages: stages(),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { ...OP_TRENDS, 'http_req_duration{op:read}': ['max>=0'] },
};

export default function () {
  reserve();
  for (let i = 0; i < READS; i++) {
    const line = cartLines()[0];
    const response = http.get(`${BASE_URL}/api/v1/shops/${SHOP_ID}/inventory/${line.inventoryItemId}`
      + `/locations/${line.locationId}?consistency=${CONSISTENCY}`, { tags: { op: 'read' } });
    (response.status === 200 ? readOk : readFailed).add(1);
    if (response.headers['X-Read-Source'] === 'replica') {
      readFromReplica.add(1);
    }
  }
}
