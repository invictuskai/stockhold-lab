-- 旧系统（文章中被替换的 Redis 方案）的整单预留：一个脚本内原子地检查并扣减所有维度的可售计数。
-- KEYS[1] 预留哈希  KEYS[2] 到期 ZSET  KEYS[3..] 各维度可售计数（按 InventoryKey 升序）
-- ARGV[1] 规范化请求 SHA-256  ARGV[2] 编码后的明细  ARGV[3] TTL 秒  ARGV[4] 幂等键  ARGV[5..] 各维度数量
local existing = redis.call('HGET', KEYS[1], 'hash')
if existing then
  if existing ~= ARGV[1] then
    return {'CONFLICT', ''}
  end
  return {'REPLAYED', redis.call('HGET', KEYS[1], 'status')}
end
local dimensions = #KEYS - 2
for i = 1, dimensions do
  local available = redis.call('GET', KEYS[i + 2])
  if not available then
    return {'NOT_FOUND', tostring(i)}
  end
  if tonumber(available) < tonumber(ARGV[i + 4]) then
    return {'INSUFFICIENT', tostring(i)}
  end
end
for i = 1, dimensions do
  redis.call('DECRBY', KEYS[i + 2], ARGV[i + 4])
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expiresAt = now + tonumber(ARGV[3]) * 1000
redis.call('HSET', KEYS[1], 'hash', ARGV[1], 'status', 'ACTIVE', 'lines', ARGV[2], 'expiresAt', expiresAt,
  'createdAt', now)
redis.call('ZADD', KEYS[2], expiresAt, ARGV[4])
return {'CREATED', 'ACTIVE'}
