
-- GateKeeper LLM TPM reservation (token bucket in milli-tokens).
-- KEYS[1]: hash with fields {tokens, ts}; tokens are milli-tokens.
-- ARGV[1]: capacity_millitokens
-- ARGV[2]: refill_milli_per_sec
-- ARGV[3]: estimated_millitokens
-- ARGV[4]: reservation_ttl_ms
-- Returns: {allowed(1/0), remaining_tokens(floor(tokens/1000)), retry_after_ms, reset_ms}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local estimated = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)

local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local ts = tonumber(redis.call('HGET', key, 'ts'))
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local elapsed = now - ts
if elapsed > 0 and refill_per_sec > 0 then
  local refill = math.floor(elapsed * refill_per_sec / 1000)
  if refill > 0 then
    if tokens + refill > capacity then
      tokens = capacity
    else
      tokens = tokens + refill
    end
    ts = now
  end
end

local allowed = 0
local retry_after_ms = 0
if tokens >= estimated then
  tokens = tokens - estimated
  allowed = 1
elseif refill_per_sec > 0 then
  retry_after_ms = math.ceil((estimated - tokens) * 1000 / refill_per_sec)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, ttl)

local reset_ms
if allowed == 1 then
  if refill_per_sec > 0 then
    reset_ms = ts + math.ceil(math.max(0, capacity - tokens) * 1000 / refill_per_sec)
  else
    reset_ms = now
  end
else
  reset_ms = now + retry_after_ms
end

return {allowed, math.floor(tokens / 1000), retry_after_ms, reset_ms}