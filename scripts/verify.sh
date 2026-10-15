#!/usr/bin/env bash
# 对若干库存维度执行只读一致性审计；任何维度 valid=false 或调用失败都以非零状态退出。
# 用法：STOCKHOLD_ADMIN_TOKEN=... scripts/verify.sh [shopId] [itemCount]
set -euo pipefail

BASE_URL="${STOCKHOLD_BASE_URL:-http://127.0.0.1:8080}"
TOKEN="${STOCKHOLD_ADMIN_TOKEN:?set STOCKHOLD_ADMIN_TOKEN}"
SHOP_ID="${1:-1001}"
ITEM_COUNT="${2:-10}"
LOCATION_ID="${STOCKHOLD_LOCATION_ID:-3001}"
FIRST_ITEM_ID="${STOCKHOLD_FIRST_ITEM_ID:-2001}"

failed=0
for ((i = 0; i < ITEM_COUNT; i++)); do
  item_id=$((FIRST_ITEM_ID + i))
  body=$(curl -sS -X POST "$BASE_URL/api/v1/admin/invariants/check" \
    -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"shopId\":$SHOP_ID,\"inventoryItemId\":$item_id,\"locationId\":$LOCATION_ID}")
  echo "item=$item_id $body"
  if [[ "$body" != *'"valid":true'* ]]; then
    failed=1
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo "INVARIANT CHECK FAILED" >&2
  exit 1
fi
echo "all invariants hold"
