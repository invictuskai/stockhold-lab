# 对若干库存维度执行只读一致性审计；任何维度 valid=false 都以非零状态退出。
# 用法：$env:STOCKHOLD_ADMIN_TOKEN='...'; scripts/verify.ps1 -ShopId 1001 -ItemCount 10
param(
    [long]$ShopId = 1001,
    [int]$ItemCount = 10,
    [long]$LocationId = 3001,
    [long]$FirstItemId = 2001
)
$ErrorActionPreference = 'Stop'

$baseUrl = if ($env:STOCKHOLD_BASE_URL) { $env:STOCKHOLD_BASE_URL } else { 'http://127.0.0.1:8080' }
if (-not $env:STOCKHOLD_ADMIN_TOKEN) { throw 'set STOCKHOLD_ADMIN_TOKEN' }
$headers = @{ 'X-Admin-Token' = $env:STOCKHOLD_ADMIN_TOKEN }

$failed = $false
for ($i = 0; $i -lt $ItemCount; $i++) {
    $itemId = $FirstItemId + $i
    $body = @{ shopId = $ShopId; inventoryItemId = $itemId; locationId = $LocationId } | ConvertTo-Json
    $report = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/admin/invariants/check" `
        -Headers $headers -ContentType 'application/json' -Body $body
    Write-Output "item=$itemId $($report | ConvertTo-Json -Compress)"
    if (-not $report.valid) { $failed = $true }
}

if ($failed) {
    Write-Error 'INVARIANT CHECK FAILED'
    exit 1
}
Write-Output 'all invariants hold'
