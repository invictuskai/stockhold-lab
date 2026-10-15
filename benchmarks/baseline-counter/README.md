# 单计数器对照组

对照方案把一个库存维度的占用压在同一行上：预留时条件更新 `baseline_inventory_counter.reserved_quantity`，所有请求竞争同一个行锁。它用来回答“单位池是否真的降低了热点 reserve 的锁等待”，不是推荐实现。

- 代码：`src/main/java/lab/inventory/benchmark/`，仅 `benchmark` profile 加载。
- 表：`src/main/resources/db/benchmark/baseline_counter.sql`，启动时 `CREATE TABLE IF NOT EXISTS`，与单位池模式的表完全独立，不混用数据。
- 幂等键、请求 hash、状态机、到期释放、错误码与单位池方案一致，并由 `BaselineCounterTest` 验证；对比的不是“裸 UPDATE”。

## 启动与准备数据

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,benchmark

# 单位池方案的数据
STOCKHOLD_ADMIN_TOKEN=... scripts/seed.sh 1001 10 1000000 1000

# 对照组的数据（同样的 shop / item / location）
for item in $(seq 2001 2010); do
  curl -sS -X POST -H "X-Admin-Token: $STOCKHOLD_ADMIN_TOKEN" \
    "http://127.0.0.1:8080/api/v1/benchmark/counter/shops/1001/inventory/$item/locations/3001/seed?onHand=1000000"
done
```

## 接口（前缀 `/api/v1/benchmark`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/counter/shops/{shopId}/inventory/{itemId}/locations/{locationId}/seed?onHand=N` | 重置计数器（管理） |
| GET | `/counter/shops/{shopId}/inventory/{itemId}/locations/{locationId}` | 读取 H 与 R |
| POST | `/counter/shops/{shopId}/reservations` | 预留，请求体与 `Idempotency-Key` 同主接口 |
| POST | `/counter/shops/{shopId}/reservations/{id}/claim` | 确认扣减 |
| POST | `/counter/shops/{shopId}/reservations/{id}/cancel` | 取消 |
| POST | `/hold?mode=connection\|outside&millis=100` | 连接持有注入（管理，时长上限 1000ms） |

k6 脚本用 `-e MODE=counter` 切换到对照组，其余参数保持一致。
