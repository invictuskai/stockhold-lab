# StockHold Lab

**基于 Java / MySQL 的高并发库存预留实验项目。**

受 Shopify 技术文章 [We replaced Redis with MySQL for inventory reservations—and it scaled](https://shopify.engineering/scaling-inventory-reservations) 启发，探索通过有界库存单位池、`SELECT ... FOR UPDATE SKIP LOCKED` 和 MySQL 本地事务实现库存预留。

> **当前状态：详细设计阶段。** 仓库仅包含设计文档与 SQL 设计稿，尚无应用代码、构建脚本或可启动服务；数据库脚本尚未执行验证，也没有测试或压测结果。本文中的功能与技术栈均为实现计划。

本项目是独立学习与验证项目，非 Shopify 官方项目，不是其内部源码或完整生产方案的复刻。

## 为什么做这个项目

传统热点库存计数器会让大量请求竞争同一行。本项目尝试将竞争转化为对不同库存单位行的领取，并验证：

- **正确性**：并发预留不超卖，确认、取消与超时释放不重复变更库存。
- **原子性**：一个订单涉及多个商品 / 地点时，在同一个 MySQL 事务中全成功或全失败。
- **性能边界**：比较库存单位池与单行计数器方案，观察吞吐、延迟、锁等待和连接持有时间，而非预设性能结论。

## 核心设计

| 机制 | 设计要点 |
| --- | --- |
| 有界库存单位池 | 每个店铺 / 商品 / 地点的可用池默认最多 1,000 行，不将全部库存一次展开 |
| 并发领取 | 使用 `FOR UPDATE SKIP LOCKED` 跳过被其他事务锁定的单位行 |
| 库存账本 | 用 `allocated_quantity` 跟踪已发放额度，避免补充时重复发放库存 |
| 事务控制 | READ COMMITTED、复合主键、统一锁顺序、短事务 |
| 完整生命周期 | 预留、确认扣减、取消与超时释放，配合幂等键和有界重试 |
| 可观测性 | 联合分析 SQL 耗时、事务时长、连接获取等待及连接持有时间 |

### 库存不变量

定义 `H` 为未正式售出的账面库存，`A` 为已发放额度，`P` 为可用池单位数量，`R` 为 ACTIVE 预留数量，`C` 为池容量。在已提交的一致性快照中应满足：

```text
H >= A >= 0
A = P + R
0 <= P <= C
可再次预留的总量 = H - R
```

池容量限制的是可用单位行数，不是商品总库存，也不是累计预留总量。完整语义与事务协议见[总体技术设计](docs/总体技术方案.md)。

## 计划技术栈

- **运行时**：Java 21、Spring Boot 3.5.x（实现时锁定具体 patch 版本）。
- **持久化**：Spring JDBC、MySQL 8.4 LTS / InnoDB、HikariCP、Flyway。
- **构建与测试**：Maven Wrapper、JUnit 5、Testcontainers，锁语义测试使用真实 MySQL，不使用 H2 替代。
- **观测与压测**：Actuator / Micrometer、k6；可选 Prometheus / Grafana。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [项目说明](项目说明.md) | 项目定位、阶段说明与环境预检记录 |
| [总体技术设计](docs/总体技术方案.md) | 架构、数据模型、不变量、事务协议与取舍 |
| [数据库设计稿](docs/数据库表结构.sql) | 待验证的 MySQL 8.4 DDL，后续迁移为 Flyway 脚本 |
| [HTTP API 契约](docs/接口契约.md) | 接口、请求响应、幂等、状态机与错误语义 |
| [实现与验证计划](docs/实现与验证计划.md) | 目录规划、开发里程碑、并发测试与压测方案 |

## 获取项目

```bash
git clone https://github.com/invictuskai/stockhold-lab.git
cd stockhold-lab
```

目前可直接阅读文档，**暂不支持构建或启动应用**。请勿将 SQL 设计稿作为已验证迁移直接执行到生产数据库。

后续实现阶段需要准备 JDK 21 和 MySQL 8.4。建议使用 Docker 运行隔离数据库与 Testcontainers；Maven Wrapper、Compose 配置和实际启动命令将在应用骨架完成后补充。

## 开发路线

- [x] 形成总体技术方案、DDL 设计稿、API 契约与验证计划。
- [ ] M0：完成设计评审与关键取舍确认。
- [ ] M1：搭建 Spring Boot 工程、Maven Wrapper、Flyway 与数据库测试环境。
- [ ] M2：实现补充、预留、确认、取消及幂等闭环。
- [ ] M3：实现超时释放，验证多实例并发、竞态与故障回滚。
- [ ] M4：接入可观测性，完成单计数器对照实验及可复现压测报告。
- [ ] M5（可选）：探索 ProxySQL 归因、影子迁移与大单专用路径。

## 范围与限制

- 第一版要求同一订单涉及的库存维度位于同一个 MySQL 数据库，不实现跨分片或跨数据中心原子事务。
- 不接入真实支付渠道；通过调用确认接口模拟支付成功，不承诺外部支付与库存事务的原子性。
- 第一版不支持部分确认、部分取消或自动选择履约地点。
- 第一版整单预留最多 1,000 个单位，单维度请求数量不超过其池容量。
- `SKIP LOCKED` 未领取到足够单位不等于真实售罄，设计中区分临时竞争与库存不足。
- 生产适用性与性能收益需要后续测试证明，本项目当前不提供相关保证。

## 参考资料

- Shopify Engineering：[We replaced Redis with MySQL for inventory reservations—and it scaled](https://shopify.engineering/scaling-inventory-reservations)。
