package com.yomi.boot.java.component.token.store;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按用户撤销的结构契约：索引与快照必须由同一条 Lua 原子删除。
 * <p>旧实现是"Java 侧先 SMEMBERS / 删快照，再 DEL 索引"，两步之间若发生并发签发，
 * 新令牌会被留成"快照在、索引不在"的漏网会话（撤销不掉、列表也看不到）。
 * 本用例锁住该实现不再回退（真 Redis 语义由 gated 的 {@code TokenIntegrationTest} 覆盖）。</p>
 */
class RedisTokenStoreTest {

    private static final Long USER_ID = 7L;

    private static final TokenKeys KEYS = new TokenKeys("yomi:test:token");

    @Test
    void revokeByUserIdDeletesIndexAndSnapshotsInOneAtomicScript() {
        CapturingTemplate template = new CapturingTemplate();
        template.indexMembers = Set.of("hash-a", "hash-b");
        RedisTokenStore store = new RedisTokenStore(template, KEYS);

        store.deleteByUserId(USER_ID);

        // ① 索引 + 全部快照只由一条脚本负责（顺序与原子性交给 Redis）
        assertEquals(1, template.scripts.size(), "按用户撤销必须只下发一条脚本");
        assertEquals(List.of(KEYS.userTokensKey(USER_ID)), template.scriptKeys.get(0), "脚本只以用户索引键为 KEYS");
        assertEquals(KEYS.sessionKeyPrefix(USER_ID), template.scriptArgs.get(0)[0], "快照键前缀按用户拼");
        String lua = template.scripts.get(0).getScriptAsString();
        assertTrue(lua.contains("SMEMBERS") && lua.contains("DEL") && lua.contains("KEYS[1]"),
                "脚本需在自身内完成 读成员 → 删快照 → 删索引: " + lua);

        // ② 索引键不得在 Java 侧被单独删除——那正是竞态窗口的入口
        assertTrue(template.deletedKeys.stream()
                        .flatMap(Collection::stream)
                        .noneMatch(KEYS.userTokensKey(USER_ID)::equals),
                "索引删除必须留在脚本内: " + template.deletedKeys);

        // ③ 路由键无 hash tag（脚本访问不到），按撤销前读到的成员尽力清理
        assertEquals(Set.of(KEYS.routeKeyByStorageToken("hash-a"), KEYS.routeKeyByStorageToken("hash-b")),
                new HashSet<>(template.deletedKeys.get(0)));
    }

    @Test
    void revokeByUserIdStillRunsScriptWhenIndexEmpty() {
        CapturingTemplate template = new CapturingTemplate();
        RedisTokenStore store = new RedisTokenStore(template, KEYS);

        store.deleteByUserId(USER_ID);

        assertEquals(1, template.scripts.size(), "索引为空也要下发脚本（脚本自身幂等，省一次分支判断）");
        assertTrue(template.deletedKeys.isEmpty(), "没有成员时无需清理路由键");
    }

    /** 只记录命令、不连 Redis 的模板桩 */
    static class CapturingTemplate extends StringRedisTemplate {

        final List<RedisScript<?>> scripts = new ArrayList<>();

        final List<List<String>> scriptKeys = new ArrayList<>();

        final List<Object[]> scriptArgs = new ArrayList<>();

        final List<List<String>> deletedKeys = new ArrayList<>();

        Set<String> indexMembers = Set.of();

        CapturingTemplate() {
            super(proxy(RedisConnectionFactory.class));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            scripts.add(script);
            scriptKeys.add(List.copyOf(keys));
            scriptArgs.add(args);
            return (T) Long.valueOf(indexMembers.size());
        }

        @Override
        public Long delete(Collection<String> keys) {
            deletedKeys.add(List.copyOf(keys));
            return (long) keys.size();
        }

        /** 旧实现会单键删索引键：一并记录，让"索引删除不在脚本内"的断言能干净地报错 */
        @Override
        public Boolean delete(String key) {
            deletedKeys.add(List.of(key));
            return Boolean.TRUE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public SetOperations<String, String> opsForSet() {
            return (SetOperations<String, String>) Proxy.newProxyInstance(
                    RedisTokenStoreTest.class.getClassLoader(),
                    new Class<?>[]{SetOperations.class},
                    (proxy, method, args) -> "members".equals(method.getName()) ? indexMembers : null);
        }

        private static RedisConnectionFactory proxy(Class<RedisConnectionFactory> type) {
            return type.cast(Proxy.newProxyInstance(RedisTokenStoreTest.class.getClassLoader(),
                    new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "stub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    }));
        }
    }

}
