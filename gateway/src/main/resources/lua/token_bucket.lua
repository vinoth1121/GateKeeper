-- GateKeeper token bucket.
-- KEYS[1]: bucket hash key  (HASH fields: tokens = milli-tokens, ts = epoch ms)
-- ARGV[1]: capacity (milli-tokens)
-- ARGV[2]: refill rate (milli-tokens per second)
-- ARGV[3]: request cost (milli-tokens)
-- ARGV[4]: max TTL seconds
-- Returns: {allowed(1/0), remaining, retry_after_ms, reset_epoch_ms}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local max_ttl = tonumber(ARGV[4])

local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1]) or capacity
local ts = tonumber(state[2]) or now

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
if refill > 0 then
    -- Cap elapsed slightly above a full refill so elapsed*refill cannot overflow.
    local full_refill_ms = math.ceil(capacity * 1000 / refill)
    if elapsed > full_refill_ms + 1000 then elapsed = full_refill_ms + 1000 end
end

local gain = math.floor(elapsed * refill / 1000)
tokens = tokens + gain
if tokens > capacity then tokens = capacity end
if tokens < 0 then tokens = 0 end

local allowed = 0
local retry_after = 0
if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
elseif refill > 0 then
    retry_after = math.ceil((cost - tokens) * 1000 / refill)
else
    retry_after = max_ttl * 1000
end

local remaining = math.floor(tokens / 1000)
local reset_ms = now
if refill > 0 then
    reset_ms = now + math.ceil((capacity - tokens) * 1000 / refill)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)

-- TTL ~= 2x full-refill time, clamped to [60s, max_ttl]
local ttl = 60
if refill > 0 then
    ttl = math.ceil(capacity * 2 * 1000 / refill / 1000)
end
if ttl < 60 then ttl = 60 end
if ttl > max_ttl then ttl = max_ttl end
redis.call('EXPIRE', key, ttl)

return {allowed, remaining, retry_after, reset_ms}
