-- 旧系统的确认：额度在预留时已从可售计数扣除，确认只改变状态。支付引用在店铺内唯一。
-- KEYS[1] 预留哈希  KEYS[2] 到期 ZSET  KEYS[3] 已用支付引用集合
-- ARGV[1] 支付引用  ARGV[2] 幂等键
local status = redis.call('HGET', KEYS[1], 'status')
if not status then
  return {'NOT_FOUND', ''}
end
if status == 'CLAIMED' then
  if redis.call('HGET', KEYS[1], 'paymentReference') == ARGV[1] then
    return {'OK', status}
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
return {'OK', 'CLAIMED'}
