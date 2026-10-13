-- GateKeeper sliding window log.
-- KEYS[1]: zset key, KEYS[2]: counter key (same hash tag)
-- ARGV[1]: limit, ARGV[2]: window_ms, ARGV[3]: cost
-- Returns: {allowed(1/0), remaining, retry_after_ms, reset_epoch_ms}

local zset = KEYS[1]
local counter = KEYS[2]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

redis.call('ZREMRANGEBYSCORE', zset, '-inf', now - window)
local count = redis.call('ZCARD', zset)

if count + cost > limit then
    local retry_after = window
    local reset_ms = now + window
    local oldest = redis.call('ZRANGE', zset, 0, 0, 'WITHSCORES')
    if oldest and #oldest >= 2 then
        local oldest_score = tonumber(oldest[2])
        retry_after = oldest_score + window - now
        if retry_after < 1 then retry_after = 1 end
        reset_ms = oldest_score + window
    end
    local remaining = limit - count
    if remaining < 0 then remaining = 0 end
    return {0, remaining, retry_after, reset_ms}
end

for i = 1, cost do
    local id = redis.call('INCR', counter)
    redis.call('ZADD', zset, now + i, tostring(now) .. ':' .. tostring(id))
end

redis.call('PEXPIRE', zset, window * 2 + 1000)
redis.call('PEXPIRE', counter, window * 2 + 1000)

return {1, limit - count - cost, 0, now + window}
