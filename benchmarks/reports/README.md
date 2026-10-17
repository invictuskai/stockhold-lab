# 压测报告

| 报告 | 内容 |
| --- | --- |
| [2026-10-15 单位池 vs 单计数器](2026-10-15-unit-pool-vs-counter.md) | 单机（M1 Max + colima MySQL 8.4）上的 E1–E6：热点预留、完整生命周期、UNION ALL、连接持有注入、池容量、连接优化前后 |
| [2026-10-16 扩展性与故障演练](2026-10-16-scaling-and-drills.md) | E7–E11：Hikari 连接数扫描、多应用实例、进程崩溃重启、ProxySQL 连接归因、Redis → MySQL 影子迁移 |
| [2026-10-17 文章完整落地](2026-10-17-article-completion.md) | E12–E16、E10X 与演练：InnoDB 线程并发度、读副本、跨结账批量、安全的邻居、补满即继续、长事务耗尽 ProxySQL 后端、按 pod 逐步上线 |

原始数据在 [raw/](raw)，用 [`benchmarks/run_matrix.py`](../run_matrix.py)（压测矩阵）与 [`benchmarks/drills.py`](../drills.py)（崩溃 / ProxySQL / 迁移演练）生成、[`benchmarks/summarize.py`](../summarize.py) 汇总。所有结果都是单机相对比较，不是生产容量结论。

每次实验新建一个 `YYYY-MM-DD-<主题>.md`，按以下模板填写。只有 QPS 截图的报告不合格。

## 模板

### 环境

- CPU / 内存 / 操作系统 / 容器资源限制：
- JDK、MySQL、应用 commit：
- 有效事务隔离级别（`SELECT @@transaction_isolation`）：
- MySQL `innodb_thread_concurrency`、`max_connections`、`innodb_lock_wait_timeout`：
- Hikari 最大连接、HTTP 线程数、`stockhold.reserve.*` 配置：

### 负载

- 脚本与完整命令（含全部 `-e` 参数）：
- 模式：单位池 / 单计数器；SQL 模式：逐维度 SELECT / UNION ALL：
- 商品分布、并发、池容量、单维度数量、购物车维度数：
- 预热与测量时长、重复次数（计划为 30s + 120s × 3；降级需写明实际值）：

### 结果（每次重复单独列出）

| 指标 | 第 1 次 | 第 2 次 | 第 3 次 |
| --- | --- | --- | --- |
| 尝试 QPS | | | |
| 成功 QPS（`reserve_created`） | | | |
| P50 / P95 / P99 | | | |
| 409 真实不足比例 | | | |
| 503 BUSY / 429 / 超时 | | | |
| reserve / claim / refill 的事务数与连接持有时间 | | | |
| 连接获取等待、Hikari pending 峰值 | | | |
| MySQL CPU、Threads_running、锁等待、死锁数 | | | |
| 同步补充次数与每次补充数量 | | | |

应用侧指标来自 `/actuator/prometheus`：`stockhold_operation_seconds`、`stockhold_transaction_seconds`、`stockhold_connection_acquire_seconds`、`stockhold_connection_hold_seconds`、`stockhold_sql_seconds`、`stockhold_reserve_retries_total`、`stockhold_reserve_pool_miss_total`、`stockhold_refill_units`、`hikaricp_connections_*`。数据库侧用 `scripts/diagnostics.sql`。

### 正确性

- 结束时各维度的 H / A / P / R：
- `scripts/verify.sh` 输出（必须全部 `valid=true`）：
- reserve-only 负载结束时的 R，以及库存是否在测量窗口内耗尽：

### 结论与局限

- 观察到的现象与可能解释（区分事实与推测）：
- 本次实验不能说明的问题：
