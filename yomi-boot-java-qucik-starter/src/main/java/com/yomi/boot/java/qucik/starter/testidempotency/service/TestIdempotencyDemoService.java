package com.yomi.boot.java.qucik.starter.testidempotency.service;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.idempotency.IdempotencyUtils;
import com.yomi.boot.java.qucik.starter.testidempotency.dto.TestIdempotencyInvokeResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 幂等演示业务：标准入口 {@link IdempotencyUtils} 包住带副作用的写逻辑，供测试接口调用
 * <p>事务边界在用例入口（组件不自建事务）：这里用 {@code @Transactional}，守卫会沿用它
 *
 * @author zwei
 */
@Service
public class TestIdempotencyDemoService {

    /** 演示命名空间 */
    public static final String NAMESPACE = "test:idempotency";

    /** 演示计数最多保留的条目数：反复调用也不无界增长（真实业务应换带上限/TTL 的缓存） */
    private static final int MAX_TRACKED_KEYS = 1000;

    /** 键：idempotencyKey（超过上限按最久未访问淘汰） */
    private static final Map<String, AtomicInteger> SIDE_EFFECTS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                /** LRU 缓存满时淘汰最旧条目 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

    /** 幂等写入口：同一命名空间 + 幂等键只产生一次副作用，重放返回首次结果 */
    @Transactional(rollbackFor = Exception.class)
    public TestIdempotencyInvokeResult create(String idempotencyKey) {
        return IdempotencyUtils.execute(NAMESPACE, idempotencyKey, TestIdempotencyInvokeResult.class,
                () -> recordSideEffect(idempotencyKey));
    }

    /** 无幂等包装的纯业务逻辑（真实业务里就是写库、发消息等副作用） */
    private TestIdempotencyInvokeResult recordSideEffect(String idempotencyKey) {
        int count = SIDE_EFFECTS.computeIfAbsent(idempotencyKey, k -> new AtomicInteger(0)).incrementAndGet();
        return new TestIdempotencyInvokeResult(IdUtils.nextId(), count);
    }

}
