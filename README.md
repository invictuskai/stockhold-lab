# StockHold Lab

**基于 Java / MySQL 的高并发库存预留实验项目。**

受 Shopify 技术文章 [We replaced Redis with MySQL for inventory reservations—and it scaled](https://shopify.engineering/scaling-inventory-reservations) 启发，探索通过有界库存单位池、`SELECT ... FOR UPDATE SKIP LOCKED` 和 MySQL 本地事务实现库存预留。

> **当前状态：文章描述的机制已全部落地（逐条对照见[总体技术方案第 2 节](docs/总体技术方案.md#2-与文章的一致点及项目补充)），170 个自动化测试在目标版本 MySQL 8.4.11 上全部通过（compose 与 Testcontainers 两条路径都已验证），其中包括用 `performance_schema.data_locks` 复现文章锁结论的测试；M4 的代码与脚本已就绪，压测见[压测报告](benchmarks/reports/README.md)。** 验证记录见[实现与验证计划第 8 节](docs/实现与验证计划.md#8-环境与当前验证状态)。

本项目是独立学习与验证项目，非 Shopify 官方项目，不是其内部源码或完整生产方案的复刻。

## 为什么做这个项目

传统热点库存计数器会让大量请求竞争同一行。本项目尝试将竞争转化为对不同库存单位行的领取，并验证：

- **正确性**：并发预留不超卖，确认、取消与超时释放不重复变更库存。
- **原子性**：一个订单涉及多个商品 / 地点时，在同一个 MySQL 事务中全成功或全失败。
- **性能边界**：比较库存单位池与单行计数器方案，观察吞吐、延迟、锁等待和连接持有时间，而非预设性能结论。单机结果：单热点预留吞吐约为单计数器的 2.4 倍，完整生命周期下只领先约 10%，详见[压测报告](benchmarks/reports/2026-10-15-unit-pool-vs-counter.md)。

## 核心设计

| 机制 | 设计要点 |
| --- | --- |
| 有界库存单位池 | 每个店铺 / 商品 / 地点的可用池默认最多 1,000 行，不将全部库存一次展开 |
| 多地点 | 一行可给出按优先级排列的候选地点，整行落在第一个能履约的地点 |
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

## 技术栈

- **运行时**：Java 21、Spring Boot 3.5.16。
- **持久化**：Spring JDBC、MySQL 8.4 LTS / InnoDB、HikariCP、Flyway。
- **构建与测试**：Maven Wrapper、JUnit 5、Testcontainers，锁语义测试使用真实 MySQL，不使用 H2 替代。
- **观测与压测**：Actuator / Micrometer、k6；可选 Prometheus / Grafana。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [项目说明](项目说明.md) | 项目定位、阶段说明与环境预检记录 |
| [总体技术设计](docs/总体技术方案.md) | 架构、数据模型、不变量、事务协议与取舍 |
| [数据库设计稿](docs/数据库表结构.sql) | 带完整注释的 DDL；实际迁移为 `src/main/resources/db/migration/V1__create_inventory_schema.sql` |
| [HTTP API 契约](docs/接口契约.md) | 接口、请求响应、幂等、状态机与错误语义 |
| [实现与验证计划](docs/实现与验证计划.md) | 目录规划、开发里程碑、并发测试与压测方案 |
| [对照组说明](benchmarks/baseline-counter/README.md) | 单计数器对照组的接口与数据准备 |
| [压测报告模板](benchmarks/reports/README.md) | 报告要求；目前没有任何结果 |

## 快速开始

需要 JDK 21 和一个 MySQL 8.x 实例（目标 8.4 LTS）。Maven 由 Wrapper 自动下载。

```bash
git clone https://github.com/invictuskai/stockhold-lab.git
cd stockhold-lab

# 1. 准备数据库：用 Docker，或自备一个隔离的 MySQL 并创建 stockhold 库与账号
cp .env.example .env          # 填写本地密码与管理令牌
docker compose up -d          # MySQL 8.4，只绑定 127.0.0.1:33306；首次初始化同时创建 stockhold_test 库与观察账号

# 2. 启动（dev profile 开放管理接口，需要 X-Admin-Token）
set -a; source .env; set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. 初始化库存、预留、审计
scripts/seed.sh 1001 10 100000 1000
curl -X POST http://127.0.0.1:8080/api/v1/shops/1001/reservations \
  -H 'Idempotency-Key: checkout-001' -H 'Content-Type: application/json' \
  -d '{"lines":[{"inventoryItemId":2001,"locationId":3001,"quantity":3}]}'
scripts/verify.sh 1001 10
```

- 接口见 [HTTP API 契约](docs/接口契约.md)；运行时 OpenAPI 文档在 `/v3/api-docs`。
- 指标在 `/actuator/prometheus`：`stockhold_operation_seconds`、`stockhold_transaction_seconds`、`stockhold_connection_acquire_seconds`、`stockhold_connection_hold_seconds`、`stockhold_sql_seconds` 等，均按固定业务标签 `op` 归因。
- 数据库侧诊断用 [scripts/diagnostics.sql](scripts/diagnostics.sql)（需要独立的管理账号）。
- 请勿把本项目的迁移直接执行到生产数据库。

### 运行测试

锁语义测试只在真实 MySQL 上运行，不用 H2。两种方式二选一：

```bash
# A. 指向一个可随时重置的隔离数据库（compose 已创建 stockhold_test）
set -a; source .env; set +a
export STOCKHOLD_TEST_JDBC_URL=jdbc:mysql://127.0.0.1:33306/stockhold_test
export STOCKHOLD_TEST_DB_USER=$STOCKHOLD_DB_USER STOCKHOLD_TEST_DB_PASSWORD=$STOCKHOLD_DB_PASSWORD
./mvnw test

# B. 不设置上述变量：Docker 可用时自动启动 Testcontainers MySQL 8.4（迁移测试另启 Redis 7.4）
./mvnw test
```

使用 colima 时，Testcontainers 需要 `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 与 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`；并在 `~/.colima/default/colima.yaml` 中设置 `docker: {ip: 127.0.0.1}`，让发布端口只绑定 IPv4 回环（默认同时绑定 `0.0.0.0` 与 `[::]` 时，本机实测 lima 的转发对 IPv4 不可达）。迁移测试（`MigrationIntegrationTest`）需要 Redis：方式 A 设置 `STOCKHOLD_TEST_REDIS_PORT`（`docker compose --profile migration up -d` 启动的 Redis 为 36379），方式 B 自动启动容器。副本读测试（`ReplicaReadsTest`）在设置 `STOCKHOLD_TEST_REPLICA_JDBC_URL`（`docker compose --profile replica up -d && scripts/replica-setup.sh` 后为 `jdbc:mysql://127.0.0.1:33307/stockhold_test`）时验证真实副本，否则验证回落到主库。

两者都不可用时，集成与并发测试会被**跳过**（只跑单元测试），请留意输出里的 Skipped 数量。锁形态测试（`ReserveLockShapeTest`、`ArticleLockPremiseTest`）还需要能读 `performance_schema` 的观察账号：方式 A 读取 `STOCKHOLD_TEST_OBSERVER_USER / PASSWORD`，方式 B 使用容器 root；业务账号不授予该权限。随机状态机测试失败时会打印种子，可用 `-Dstockhold.test.seed=<seed>` 复现。

### 压测

k6 脚本在 [benchmarks/k6](benchmarks/k6)，对照组需要 `dev,benchmark` profile，详见[对照组说明](benchmarks/baseline-counter/README.md)。整套实验可用 `python3 benchmarks/run_matrix.py --out <目录>` 复现（自动启停应用、清库、播种、预热、测量与审计）。多维度预留的 UNION ALL 锁定读由 `STOCKHOLD_UNION_ALL_SELECT=true` 打开，默认关闭。

## 开发路线

- [x] 形成总体技术方案、DDL 设计稿、API 契约与验证计划。
- [x] M1：Spring Boot 工程、Maven Wrapper、Flyway 迁移、Compose 与数据库测试环境。
- [x] M2：补充、预留、确认、取消及幂等闭环。
- [x] M3：超时释放、并发争抢、补充竞态、状态竞争、故障回滚、死锁 / 锁等待处理、随机状态机测试。
- [x] M4：业务标签与连接获取 / 持有 / 事务 / SQL 耗时指标、单计数器对照组、UNION ALL 可选路径、k6 脚本与可复现的压测执行器；单机压测报告见 [benchmarks/reports](benchmarks/reports/README.md)。
- [x] M5（可选）：ProxySQL 连接归因、Redis → MySQL 影子迁移演练、多实例与进程崩溃演练，见[扩展性与故障演练报告](benchmarks/reports/2026-10-16-scaling-and-drills.md)。
- [x] M6：对照原文补齐其余机制——候选地点（只从能履约的地点预留）、跨结账批量预留、读副本、池补满即继续、旧架构两步确认的超卖 / 少卖复现、RR 死锁与锁序死锁复现、长事务耗尽 ProxySQL 后端的定位、InnoDB 线程并发度、安全的邻居、按 pod 逐步上线，见[文章完整落地报告](benchmarks/reports/2026-10-17-article-completion.md)。大单专用路径（文章未涉及）未做。
- [x] 修复验证中发现的问题：到期清理改为 SKIP LOCKED 批量领取、读副本延迟保护、批量预留成员隔离、Flyway 升级、测试库清理，见[修复报告](benchmarks/reports/2026-10-18-fixes.md)。

尚未验证的部分：独立数据库服务器上的性能（所有压测中 MySQL 与应用、k6 同机）、生产规模的性能、大单专用路径（文章未涉及）。

## 范围与限制

- 第一版要求同一订单涉及的库存维度位于同一个 MySQL 数据库，不实现跨分片或跨数据中心原子事务。
- 不接入真实支付渠道；通过调用确认接口模拟支付成功，不承诺外部支付与库存事务的原子性。
- 第一版不支持部分确认、部分取消或自动选择履约地点。
- 第一版整单预留最多 1,000 个单位，单维度请求数量不超过其池容量。
- `SKIP LOCKED` 未领取到足够单位不等于真实售罄，设计中区分临时竞争与库存不足。
- 取消 / 到期释放的额度先回到账本未分配额度，由下一次补充重新变为可领取单位。
- 生产适用性与性能收益需要后续压测证明，本项目当前不提供相关保证。

## 参考资料

- Shopify Engineering：[We replaced Redis with MySQL for inventory reservations—and it scaled](https://shopify.engineering/scaling-inventory-reservations)。
