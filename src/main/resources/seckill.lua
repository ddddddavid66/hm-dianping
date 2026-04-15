-- 参数
-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId =  ARGV[2]
-- 订单id
local orderId = ARGV[3]

--KEY
-- 库存key
local stock = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId .. userId

-- 库存数量
local stockNum = tonumber(redis.call('get',stock) or '0') -- 防止空指针
if(stockNum <= 0) then
    return 1
end
--判断用户是否下单 SIS MEMBER
if(redis.call('sismember',orderKey,userId) == 1)  then
    return 2
end
-- 库存扣除
redis.call('incrby',stock,-1)
-- 创建订单
redis.call('sadd',orderKey,userId)

-- 向stream 队列 里面发消息
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId);
return 0
