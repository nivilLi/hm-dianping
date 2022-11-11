--
-- Created by IntelliJ IDEA.
-- User: lc
-- Date: 2022/11/4
-- Time: 21:30
-- To change this template use File | Settings | File Templates.
--

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock' .. voucherId
local orderKey = 'seckill:order' .. voucherId

if(tonumber(redis.call('get', stockKey)) < 1) then
    return 1
end
if(redis.call('sisnumber', orderKey, userId) == 1) then
    return 2
end
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redsi.call('xadd', 'stream.orders', '*', 'userId',userId, 'voucherId', voucherId, 'orderId', orderId)
return 0