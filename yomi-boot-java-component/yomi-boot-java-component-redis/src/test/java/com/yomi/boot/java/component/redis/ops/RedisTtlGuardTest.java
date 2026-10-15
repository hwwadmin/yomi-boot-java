package com.yomi.boot.java.component.redis.ops;

import com.yomi.boot.java.component.redis.RedisTtl;
import com.yomi.boot.java.component.redis.config.CommonRedisTemplate;
import com.yomi.boot.java.component.redis.ops.sorted.HashRedisSupport;
import com.yomi.boot.java.component.redis.ops.sorted.SpecialOpsRedisSupport;
import com.yomi.boot.java.component.redis.ops.sorted.StringRedisSupport;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTL 入参守卫：0/负数在 Redis 与 Spring Data 语义下是"写成永久键 / 直接删键 / 抹掉原过期时间"，
 * 必须在组件入口被挡下。正数路径不得受影响（模板用吞命令的桩，不连 Redis）。
 */
class RedisTtlGuardTest {

    private final SilentStringTemplate stringTemplate = new SilentStringTemplate();

    private final SilentObjectTemplate objectTemplate = new SilentObjectTemplate();

    private final StringRedisSupport stringOps = new StringRedisSupport(stringTemplate);

    private final HashRedisSupport hashOps = new HashRedisSupport(objectTemplate);

    private final SpecialOpsRedisSupport specialOps = new SpecialOpsRedisSupport(objectTemplate);

    private final RedisSupport redisSupport = new RedisSupport(objectTemplate, stringTemplate, null);

    @Test
    void zeroOrNegativeTimeoutIsRejectedOnEveryEntryPoint() {
        for (long bad : new long[]{0L, -1L}) {
            assertRejectedWithMethodHint(() -> stringOps.set("k", "v", bad), "set");
            assertRejectedWithMethodHint(() -> stringOps.set("k", "v", bad, TimeUnit.SECONDS), "set");
            assertRejectedWithMethodHint(() -> stringOps.setIfAbsent("k", "v", bad, TimeUnit.SECONDS), "setIfAbsent");
            assertRejectedWithMethodHint(() -> stringOps.getAndExpire("k", bad, TimeUnit.SECONDS), "getAndExpire");
            assertRejectedWithMethodHint(() -> stringOps.getAndExpire("k", String.class, bad, TimeUnit.SECONDS), "getAndExpire");
            assertRejectedWithMethodHint(() -> hashOps.put("k", "h", "v", bad, TimeUnit.SECONDS), "put");
            assertRejectedWithMethodHint(() -> hashOps.putAll("k", Map.of("h", "v"), bad, TimeUnit.SECONDS), "putAll");
            assertRejectedWithMethodHint(() -> specialOps.incr("k", bad, TimeUnit.SECONDS), "incr");
            assertRejectedWithMethodHint(() -> specialOps.incr("k", 2L, bad, TimeUnit.SECONDS), "incr");
            assertRejectedWithMethodHint(() -> redisSupport.expire("k", bad, TimeUnit.SECONDS), "expire");
        }
    }

    @Test
    void positiveTimeoutPassesThroughUnchanged() {
        assertEquals(30L, RedisTtl.requirePositive(30L, "probe"), "守卫必须原值透传");

        assertDoesNotThrow(() -> stringOps.set("k", "v", 30));
        assertDoesNotThrow(() -> stringOps.set("k", "v", 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> stringOps.setIfAbsent("k", "v", 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> stringOps.getAndExpire("k", 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> stringOps.getAndExpire("k", String.class, 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> hashOps.put("k", "h", "v", 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> hashOps.putAll("k", Map.of("h", "v"), 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> specialOps.incr("k", 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> specialOps.incr("k", 2L, 30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> redisSupport.expire("k", 30, TimeUnit.SECONDS));
    }

    /** 断言被拒绝，且报错点名调用方方法（便于定位是哪个入口） */
    private static void assertRejectedWithMethodHint(Runnable action, String method) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(ex.getMessage().contains(method), "报错应点名方法 " + method + ": " + ex.getMessage());
        assertTrue(ex.getMessage().contains("必须大于 0"), ex.getMessage());
    }

    /** 吞命令的字符串模板：opsForValue 用接口代理，避免真连 Redis */
    static class SilentStringTemplate extends StringRedisTemplate {

        SilentStringTemplate() {
            super(stubConnectionFactory());
        }

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) swallow(ValueOperations.class);
        }
    }

    /** 吞命令的对象模板：Hash / 特殊操作 / RedisSupport 走的都是它 */
    static class SilentObjectTemplate extends CommonRedisTemplate {

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) swallow(ValueOperations.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public HashOperations<String, Object, Object> opsForHash() {
            return (HashOperations<String, Object, Object>) swallow(HashOperations.class);
        }

        @Override
        public Boolean expire(String key, Duration duration) {
            return Boolean.TRUE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            return (T) Long.valueOf(1L);
        }
    }

    private static RedisConnectionFactory stubConnectionFactory() {
        return (RedisConnectionFactory) Proxy.newProxyInstance(RedisTtlGuardTest.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class}, (proxy, method, args) -> defaultReturn(proxy, method));
    }

    @SuppressWarnings("unchecked")
    private static <T> T swallow(Class<T> type) {
        return (T) Proxy.newProxyInstance(RedisTtlGuardTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> defaultReturn(proxy, method));
    }

    private static Object defaultReturn(Object proxy, Method method) {
        return switch (method.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> false;
            default -> method.getReturnType().isPrimitive() ? primitiveDefault(method.getReturnType()) : null;
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == double.class) return 0D;
        return null;
    }

}
