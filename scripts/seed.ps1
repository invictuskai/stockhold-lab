# 通过管理 API 初始化若干库存维度并预填池。需要应用以 dev profile 运行。
# 用法：$env:STOCKHOLD_ADMIN_TOKEN='...'; scripts/seed.ps1 -ShopId 1001 -ItemCount 10
param(
    [long]$ShopId = 1001,
    [int]$ItemCount = 10,
    [long]$InitialQuantity = 100000,
    [int]$PoolCapacity = 1000,
    [long]$LocationId = 3001,
    [long]$FirstItemId = 2001
)
$ErrorActionPreference = 'Stop'

$baseUrl = if ($env:STOCKHOLD_BASE_URL) { $env:STOCKHOLD_BASE_URL } else { 'http://127.0.0.1:8080' }
if (-not $env:STOCKHOLD_ADMIN_TOKEN) { throw 'set STOCKHOLD_ADMIN_TOKEN' }
$headers = @{ 'X-Admin-Token' = $env:STOCKHOLD_ADMIN_TOKEN }

for ($i = 0; $i -lt $ItemCount; $i++) {
    $itemId = $FirstItemId + $i
    # operationId 由维度确定性派生：重复执行脚本是幂等的，不会重复加库存。
    $suffix = '{0:D12}' -f (($ShopId % 100000) * 1000000 + ($itemId % 1000000))
    $body = @{
        operationId     = "00000000-0000-4000-8000-$suffix"
        inventoryItemId = $itemId
        locationId      = $LocationId
        initialQuantity = $InitialQuantity
        poolCapacity    = $PoolCapacity
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/shops/$ShopId/inventory" `
        -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    Invoke-RestMethod -Method Post -Headers $headers `
        -Uri "$baseUrl/api/v1/shops/$ShopId/inventory/$itemId/locations/$LocationId/refill" | Out-Null
    Write-Output "shop=$ShopId item=$itemId location=$LocationId ready"
}
