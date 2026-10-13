
-- GateKeeper LLM TPM reconciliation after actual usage is known.
-- KEYS[1]: hash with fields {tokens, ts}; tokens are milli-tokens.
-- ARGV[1]: capacity_millitokens
-- ARGV[2]: refill_milli_per_sec
-- ARGV[3]: diff_millitokens (actual - estimated; negative = refund, positive = charge)
-- ARGV[4]: ttl_ms
-- Returns: {remaining_tokens(floor(tokens/1000)), refunded(1/0), charged(1/0)}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local diff = tonumber(ARGV[3])
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

local refunded = 0
local charged = 0
if diff > 0 then
  tokens = tokens - diff
  if tokens < 0 then
    tokens = 0
  end
  charged = 1
elseif diff < 0 then
  tokens = tokens - diff
  if tokens > capacity then
    tokens = capacity
  end
  refunded = 1
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, ttl)

return {math.floor(tokens / 1000), refunded, charged}