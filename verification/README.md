# 设计验证脚本

用 Node.js 按 [总体技术方案](../docs/总体技术方案.md) 第 7 节的事务协议写的**参考实现与验证脚本**，连接真实 MySQL 8.4 执行。它们用于证明设计可行，不是生产代码；正式实现仍按计划使用 Java / Spring JDBC。

## 运行

前提：一个**专用、可清空**的 MySQL 8.4 实例（脚本会 DELETE 表数据，切勿指向任何有价值的数据库）。

```bash
# 1. 建库并执行设计稿 DDL
mysql -uroot -h127.0.0.1 -P33306 -e "CREATE DATABASE stockhold"
mysql -uroot -h127.0.0.1 -P33306 --default-character-set=utf8mb4 stockhold < ../docs/数据库表结构.sql

# 2. 安装驱动并运行
npm install
npm run locks              # 锁行为与 CHECK 约束（28 项）
npm run flashsale:batched  # 秒杀全流程并发模拟，BATCHED 结算
npm run flashsale:sync     # 同上，SYNC 结算（对照）
npm run partitions         # 逻辑库存分区并发：多分区、在途划拨、关闭与退回、三种范围限购
npm run partition-edges    # 分区与限购的确定性边界用例（9 项）
npm run bench              # 单热点 SKU 吞吐与延迟
```

连接参数通过环境变量覆盖：`MYSQL_HOST`、`MYSQL_PORT`（默认 33306）、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_DATABASE`（默认 stockhold）。模拟规模可用 `STOCK`、`REQ`、`BUYERS`、`CONC`、`N` 调整。

## 文件

| 文件 | 内容 |
| --- | --- |
| lib.js | reserve / refill（含 single-flight 与售罄缓存）/ claim / lateClaim / release / 结算 / 审计的参考实现 |
| locks.js | 复合主键与自增主键锁数量、SKIP LOCKED、RC 与 RR 空池间隙锁、未提交删除可见性、UNION ALL、CHECK 约束正反例、买家额度并发 |
| flashsale.js | 秒杀模拟：限购、幂等重放、确认 / 取消 / 超时 / 迟到确认、热点补充、过期扫描、结算，结束后做全量审计 |
| partitions.js | 同一 SKU 在默认分区和两个分区并发销售，在途追加划拨，关闭分区并由退回任务退回余量，四条限购规则；结束后做每维度、SKU 合计、限购、额度一致性审计 |
| partition-edges.js | 划拨回收池单位、被 ACTIVE 挡住、幂等重放、关闭后拒绝、退回 BUSY / ACTIVE 挡住、退回后迟到确认、规则中途变更 |
| bench.js | 单 SKU 的 reserve、claim、release 吞吐（SYNC vs BATCHED），以及售罄请求在有无缓存时的成本 |
| results/ | 2026-10-01 在本地 MySQL 8.4.9 上的运行结果原始 JSON |

结果解读与结论见 [秒杀场景评审与验证报告](../docs/秒杀场景评审与验证报告.md)。
