-- GateKeeper node liveness count.
-- ARGV[1]: node key pattern (gk:{sys}:nodes:*)
-- Returns: number of live node keys
return #redis.call('keys', ARGV[1])
