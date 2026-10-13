-- GateKeeper two-tier leasing: return leftover local tokens to the bucket.
-- KEYS[1]: bucket hash key (fields: tokens = milli-tokens, ts = epoch ms)
-- ARGV[1]: capacity (milli-tokens)
-- ARGV[2]: returned leftover (milli-tokens)
-- ARGV[3]: refill rate (milli-tokens per second)
-- ARGV[4]: max TTL seconds
-- Returns: {tokens_after_milli, remaining}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local returned = tonumber(ARGV[2])
local refill = tonumber(ARGV[3])
local max_ttl = tonumber(ARGV[4])
if returned < 0 then returned = 0 end

local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1]) or capacity
local ts = tonumber(state[2]) or now

-- Accrue elapsed refill, then add the returned leftover, capped at capacity.
local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
if refill > 0 then
    local full_refill_ms = math.ceil(capacity * 1000 / refill)
    if elapsed > full_refill_ms + 1000 then elapsed = full_refill_ms + 1000 end
    tokens = tokens + math.floor(elapsed * refill / 1000)
end

tokens = tokens + returned
if tokens > capacity then tokens = capacity end
if tokens < 0 then tokens = 0 end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)

-- TTL refresh, same policy as lease.lua
local ttl = 60
if refill > 0 then
    ttl = math.ceil(capacity * 2 * 1000 / refill / 1000)
end
if ttl < 60 then ttl = 60 end
if ttl > max_ttl then ttl = max_ttl end
redis.call('EXPIRE', key, ttl)

return {tokens, math.floor(tokens / 1000)}
