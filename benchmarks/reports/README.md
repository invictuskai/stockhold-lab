# 压测报告

**目前没有任何压测结果。** 仓库只提供了可执行的 k6 脚本、对照组实现和下面的报告模板；本机未安装 k6，尚未运行过任何一组负载，因此不存在可以引用的 QPS、延迟或“单位池优于计数器”的结论。

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
