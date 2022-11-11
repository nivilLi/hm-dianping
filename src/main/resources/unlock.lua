--lock_name
local key = KEYS[1]

--get Thread sign
local id = redis.call('get', key)

--current thread sign
local currentThreadId = ARGV[1]

--compare identify

if(id == currentThreadId)
then
    return redis.call('del', key)
end
return 0