-- 旧系统确认中的 Redis 一步：额度在预留时已从可售计数扣除，这里只改状态并减少已预留计数。
-- 账本扣减在 MySQL 中另行执行，两步无法原子提交（文章中旧架构的根本问题）。
-- KEYS[1] 预留哈希  KEYS[2] 到期 ZSET  KEYS[3] 已用支付引用集合  KEYS[4..] 各维度已预留计数
-- ARGV[1] 支付引用  ARGV[2] 幂等键  ARGV[3..] 各维度数量
local status = redis.call('HGET', KEYS[1], 'status')
if not status then
  return {'NOT_FOUND', ''}
end
if status == 'CLAIMED' then
  if redis.call('HGET', KEYS[1], 'paymentReference') == ARGV[1] then
    -- 重复确认：结果与首次相同，但调用方不得再次扣减账本。
    return {'OK', 'ALREADY_CLAIMED'}
  end
  return {'PAYMENT_CONFLICT', status}
end
if status == 'CANCELLED' then
  return {'INVALID_STATE', status}
end
if status == 'EXPIRED' then
  return {'EXPIRED', status}
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if tonumber(redis.call('HGET', KEYS[1], 'expiresAt')) <= now then
  return {'EXPIRED', status}
end
if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then
  return {'PAYMENT_CONFLICT', status}
end
redis.call('SADD', KEYS[3], ARGV[1])
redis.call('HSET', KEYS[1], 'status', 'CLAIMED', 'paymentReference', ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[2])
for i = 4, #KEYS do
  redis.call('DECRBY', KEYS[i], ARGV[i - 1])
end
return {'OK', 'CLAIMED'}
