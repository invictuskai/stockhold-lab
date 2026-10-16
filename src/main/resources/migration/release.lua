-- 旧系统的取消 / 到期释放：把额度加回各维度的可售计数，状态机与 MySQL 方案一致。
-- KEYS[1] 预留哈希  KEYS[2] 到期 ZSET  KEYS[3..] 各维度可售计数
-- ARGV[1] 目标状态 CANCELLED | EXPIRED  ARGV[2] 幂等键  ARGV[3..] 各维度数量
local status = redis.call('HGET', KEYS[1], 'status')
if not status then
  return {'NOT_FOUND', ''}
end
local target = ARGV[1]
if status == target then
  return {'OK', status}
end
if status == 'CLAIMED' or status == 'CANCELLED' then
  return {'INVALID_STATE', status}
end
if status == 'EXPIRED' then
  return {'EXPIRED', status}
end
if target == 'EXPIRED' then
  local time = redis.call('TIME')
  local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
  if tonumber(redis.call('HGET', KEYS[1], 'expiresAt')) > now then
    return {'NOT_DUE', status}
  end
end
for i = 3, #KEYS do
  redis.call('INCRBY', KEYS[i], ARGV[i])
end
redis.call('HSET', KEYS[1], 'status', target)
redis.call('ZREM', KEYS[2], ARGV[2])
return {'OK', target}
