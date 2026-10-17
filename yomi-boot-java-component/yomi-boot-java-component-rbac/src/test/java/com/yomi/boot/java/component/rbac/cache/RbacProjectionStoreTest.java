package com.yomi.boot.java.component.rbac.cache;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.rbac.config.RbacProperties;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.repository.RbacSnapshotRepository;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 不可用时：投影读失败回源 DB，结果写入内存缓存，陈旧度受 memTtl 约束。
 */
class RbacProjectionStoreTest {

    @Test
    void redisDownFallsBackToDbWithoutThrowing() {
        CountingSnapshotRepository repository = new CountingSnapshotRepository();
        RbacProjectionStore store = storeWithRedisDown(repository);

        RbacSnapshot snapshot = assertDoesNotThrow(store::loadSnapshot);

        assertFalse(snapshot.getPerms().isEmpty(), "Redis 故障时必须返回 DB 回源数据，而不是抛异常（否则穿透成 403）");
        assertEquals(1, repository.loads.get());
    }

    @Test
    void dbFallbackResultIsCachedInMemory() {
        CountingSnapshotRepository repository = new CountingSnapshotRepository();
        RbacProjectionStore store = storeWithRedisDown(repository);

        store.loadSnapshot();
        store.loadSnapshot();
        store.loadSnapshot();

        assertEquals(1, repository.loads.get(),
                "回源结果应进入内存缓存（memTtl 内有效）：故障期只有周期性首个请求付出回源代价");
    }

    /** Redis 活着但版本被删了一半（刷新删旧版本/内存淘汰）：行数不符按不可读处理，回源 DB 重建 */
    @Test
    void truncatedVersionFallsBackToDb() {
        CountingSnapshotRepository repository = new CountingSnapshotRepository();
        RbacProjectionStore store = new RbacProjectionStore(truncatedRedisTemplate(),
                new RbacProjectionKeys("yomi:test:rbac"), properties(), repository);

        RbacSnapshot snapshot = store.loadSnapshot();

        assertEquals(1, repository.loads.get(), "半截快照必须触发 DB 回源");
        assertEquals(1L, snapshot.getPerms().get(0).getId(),
                "返回的应是 DB 回源数据，而不是 Redis 里残留的半截行（id=999）");
    }

    static RbacProjectionStore storeWithRedisDown(RbacSnapshotRepository repository) {
        RbacProperties properties = new RbacProperties();
        properties.setMemTtl(Duration.ofSeconds(10));
        return new RbacProjectionStore(downRedisTemplate(), new RbacProjectionKeys("yomi:test:rbac"), properties,
                repository);
    }

    /** 模拟 Redis 进程不可用的模板：任何命令都抛连接异常 */
    private static StringRedisTemplate downRedisTemplate() {
        RedisConnectionFactory downFactory = (RedisConnectionFactory) Proxy.newProxyInstance(
                RbacProjectionStoreTest.class.getClassLoader(), new Class<?>[]{RedisConnectionFactory.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new RedisConnectionFailureException("redis down");
                    }
                    return null;
                });
        return new StringRedisTemplate(downFactory);
    }

    /** 桩模板：指针声称 3 行（V1），但版本里只剩 1 行 → 行数校验不通过 */
    private static StringRedisTemplate truncatedRedisTemplate() {
        RbacProjectionKeys keys = new RbacProjectionKeys("yomi:test:rbac");
        RbacCachePointer pointer = new RbacCachePointer();
        pointer.setV("V1");
        pointer.setN(3);
        String pointerJson = JsonUtils.toJson(pointer);
        String permKey = keys.permHashKey("V1");

        StringRedisTemplate template = new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
                return (org.springframework.data.redis.core.ValueOperations<String, String>) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{org.springframework.data.redis.core.ValueOperations.class},
                        (proxy, method, args) -> "get".equals(method.getName()) && keys.currentKey().equals(args[0])
                                ? pointerJson : null);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <HK, HV> org.springframework.data.redis.core.HashOperations<String, HK, HV> opsForHash() {
                return (org.springframework.data.redis.core.HashOperations<String, HK, HV>) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{org.springframework.data.redis.core.HashOperations.class},
                        (proxy, method, args) -> "entries".equals(method.getName()) && permKey.equals(args[0])
                                ? java.util.Map.of("999", "{\"id\":999}") : java.util.Map.of());
            }
        };
        return template;
    }

    /** 构造测试用 RbacProperties */
    private static RbacProperties properties() {
        RbacProperties properties = new RbacProperties();
        properties.setMemTtl(Duration.ofSeconds(10));
        return properties;
    }

    /** 快照仓储测试桩：统计回源次数，返回一条权限数据 */
    static class CountingSnapshotRepository extends RbacSnapshotRepository {

        final AtomicInteger loads = new AtomicInteger();

        CountingSnapshotRepository() {
            super(null, null, null, null, null, null);
        }

        @Override
        public RbacSnapshot loadAllForCache() {
            loads.incrementAndGet();
            RbacSnapshot snapshot = RbacSnapshot.empty();
            RbacPerm perm = new RbacPerm();
            perm.setId(1L);
            snapshot.setPerms(List.of(perm));
            return snapshot;
        }
    }

}
