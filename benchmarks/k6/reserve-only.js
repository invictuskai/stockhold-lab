// reserve-only 短窗负载。必须准备足够库存，并在结束后记录 R 与不变量检查结果：
// 库存耗尽后请求会快速返回 409，那不是吞吐提升。
//
//   k6 run -e VUS=50 -e MODE=pool    -e ITEM_COUNT=1 benchmarks/k6/reserve-only.js
//   k6 run -e VUS=50 -e MODE=counter -e ITEM_COUNT=1 benchmarks/k6/reserve-only.js
import { OP_TRENDS, reserve, stages } from './lib.js';

export const options = {
  stages: stages(),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    ...OP_TRENDS,
    // 正确性与可解释性优先，不设武断的 QPS 验收值；只要求没有未归类的错误。
    reserve_other_errors: ['count==0'],
  },
};

export default function () {
  reserve();
}
