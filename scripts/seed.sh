#!/usr/bin/env bash
# 通过管理 API 初始化若干库存维度并预填池。需要应用以 dev profile 运行。
# 用法：STOCKHOLD_ADMIN_TOKEN=... scripts/seed.sh [shopId] [itemCount] [initialQuantity] [poolCapacity]
set -euo pipefail

BASE_URL="${STOCKHOLD_BASE_URL:-http://127.0.0.1:8080}"
TOKEN="${STOCKHOLD_ADMIN_TOKEN:?set STOCKHOLD_ADMIN_TOKEN}"
SHOP_ID="${1:-1001}"
ITEM_COUNT="${2:-10}"
INITIAL_QUANTITY="${3:-100000}"
POOL_CAPACITY="${4:-1000}"
LOCATION_ID="${STOCKHOLD_LOCATION_ID:-3001}"
FIRST_ITEM_ID="${STOCKHOLD_FIRST_ITEM_ID:-2001}"

for ((i = 0; i < ITEM_COUNT; i++)); do
  item_id=$((FIRST_ITEM_ID + i))
  # operationId 由维度确定性派生：重复执行脚本是幂等的，不会重复加库存。
  operation_id=$(printf '00000000-0000-4000-8000-%012d' "$((SHOP_ID % 100000 * 1000000 + item_id % 1000000))")
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "$BASE_URL/api/v1/shops/$SHOP_ID/inventory" \
    -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"operationId\":\"$operation_id\",\"inventoryItemId\":$item_id,\"locationId\":$LOCATION_ID,\"initialQuantity\":$INITIAL_QUANTITY,\"poolCapacity\":$POOL_CAPACITY}")
  case "$status" in
    200|201) ;;
    *) echo "create item $item_id failed: HTTP $status" >&2; exit 1 ;;
  esac
  curl -sS -o /dev/null -X POST \
    "$BASE_URL/api/v1/shops/$SHOP_ID/inventory/$item_id/locations/$LOCATION_ID/refill" \
    -H "X-Admin-Token: $TOKEN"
  echo "shop=$SHOP_ID item=$item_id location=$LOCATION_ID ready (HTTP $status)"
done
