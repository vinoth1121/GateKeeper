package io.gatekeeper.resilience;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.gatekeeper.limiter.LuaScriptManager;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Node liveness: a virtual thread every 2s does SETEX gk:{sys}:nodes:<nodeId> 6 1
 * and counts live nodes via count_nodes.lua. On Redis failure the last-known
 * count is kept (default 1).
 */
public final class NodeHeartbeat {

    public static final String COUNT_NODES = "count_nodes";
    static final long TTL_SECONDS = 6;
    static final long INTERVAL_MS = 2000;

    private final StatefulRedisConnection<String, String> connection;
    private final RedisGuard guard;
    private final LuaScriptManager scripts;
    private final String key;
    private final String pattern;
    private volatile boolean running;
    private Thread thread;

    public NodeHeartbeat(StatefulRedisConnection<String, String> connection, RedisGuard guard,
            LuaScriptManager scripts, String systemId, String nodeId) {
        this.connection = connection;
        this.guard = guard;
        this.scripts = scripts;
        scripts.registerScript(COUNT_NODES, LuaScripts.read("/lua/count_nodes.lua"));
        this.key = "gk:{" + systemId + "}:nodes:" + nodeId;
        this.pattern = "gk:{" + systemId + "}:nodes:*";
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("gk-node-heartbeat").start(this::loop);
    }

    private void loop() {
        while (running) {
            guard.call("heartbeat", () -> {
                connection.sync().setex(key, TTL_SECONDS, "1");
                return Boolean.TRUE;
            }, () -> Boolean.FALSE);
            Long count = guard.call(COUNT_NODES, () -> {
                List<Object> result = scripts.eval(COUNT_NODES, List.of(), List.of(pattern));
                return ((Number) result.get(0)).longValue();
            }, () -> -1L);
            if (count != null && count >= 0) {
                guard.updateLiveNodeCount(count.intValue());
            }
            try {
                TimeUnit.MILLISECONDS.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
