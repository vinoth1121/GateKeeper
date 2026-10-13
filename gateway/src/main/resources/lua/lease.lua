-- GateKeeper two-tier leasing: grant a chunk of tokens to a local node.
-- KEYS[1]: bucket hash key (fields: tokens = milli-tokens, ts = epoch ms,
--           same format as token_bucket.lua so both scripts share a bucket)
-- ARGV[1]: capacity (milli-tokens)
-- ARGV[2]: refill rate (milli-tokens per second)
-- ARGV[3]: requested lease (milli-tokens)
-- ARGV[4]: max TTL seconds
-- Returns: {granted_milli, remaining, retry_after_ms, reset_epoch_ms}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
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

-- Grants are exact: only what the central bucket currently holds.
local granted = math.min(requested, tokens)
tokens = tokens - granted

local remaining = math.floor(tokens / 1000)
local retry_after = 0
if refill > 0 and granted < requested then
    retry_after = math.ceil((requested - tokens) * 1000 / refill)
end

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

return {granted, remaining, retry_after, reset_ms}
