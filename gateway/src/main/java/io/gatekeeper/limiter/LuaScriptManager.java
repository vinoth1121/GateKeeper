package io.gatekeeper.limiter;

import io.gatekeeper.metrics.GateKeeperMeters;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads each Lua script once, caches its SHA1 per script (AtomicReference) and
 * evaluates with EVALSHA, falling back to SCRIPT LOAD + retry on NOSCRIPT.
 * Thread-safe via the shared multiplexed Lettuce connection; no locking.
 */
public final class LuaScriptManager {

    public static final String TOKEN_BUCKET = "token_bucket";
    public static final String SLIDING_WINDOW_LOG = "sliding_window_log";

    private final StatefulRedisConnection<String, String> connection;
    private final GateKeeperMeters meters;
    private final Map<String, String> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<String>> shas = new ConcurrentHashMap<>();

    public LuaScriptManager(StatefulRedisConnection<String, String> connection, GateKeeperMeters meters) {
        this.connection = connection;
        this.meters = meters;
        registerBuiltin(TOKEN_BUCKET, "/lua/token_bucket.lua");
        registerBuiltin(SLIDING_WINDOW_LOG, "/lua/sliding_window_log.lua");
    }

    public void registerScript(String name, String content) {
        scripts.put(name, content);
        shas.put(name, new AtomicReference<>());
    }

    public List<Object> eval(String scriptName, List<String> keys, List<String> args) {
        long start = System.nanoTime();
        try {
            List<Object> result = evalInternal(scriptName, keys, args);
            return result;
        } finally {
            meters.redisCallTotal(scriptName).increment();
            meters.redisTimer(scriptName).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private List<Object> evalInternal(String scriptName, List<String> keys, List<String> args) {
        String content = scripts.get(scriptName);
        if (content == null) {
            throw new IllegalArgumentException("Unknown Lua script: " + scriptName);
        }
        if (connection == null) {
            throw new IllegalStateException("Redis connection is not available");
        }
        RedisCommands<String, String> sync = connection.sync();
        AtomicReference<String> shaRef = shas.computeIfAbsent(scriptName, n -> new AtomicReference<>());
        String sha = shaRef.get();
        if (sha == null) {
            sha = load(sync, content, shaRef);
        }
        try {
            return sync.evalsha(sha, ScriptOutputType.MULTI, keys.toArray(new String[0]), args.toArray(new String[0]));
        } catch (RedisNoScriptException e) {
            sha = load(sync, content, shaRef);
            return sync.evalsha(sha, ScriptOutputType.MULTI, keys.toArray(new String[0]), args.toArray(new String[0]));
        }
    }

    private String load(RedisCommands<String, String> sync, String content, AtomicReference<String> shaRef) {
        String sha = sync.scriptLoad(content);
        shaRef.set(sha);
        return sha;
    }

    private void registerBuiltin(String name, String resourcePath) {
        try (InputStream in = LuaScriptManager.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing Lua resource on classpath: " + resourcePath);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            registerScript(name, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Lua resource: " + resourcePath, e);
        }
    }
}
