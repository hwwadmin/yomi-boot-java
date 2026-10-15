package com.yomi.boot.java.qucik.starter.testidempotency.service;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.idempotency.annotation.Idempotent;
import com.yomi.boot.java.qucik.starter.testidempotency.dto.TestIdempotencyInvokeResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 幂等演示业务：用内存计数模拟副作用，供测试接口调用
 *
 * @author zwei
 */
@Service
public class TestIdempotencyDemoService {

    /** 注解路径使用的命名空间（与 Controller 中 utils 路径区分） */
    public static final String NAMESPACE_ANNOTATION = "test:idempotency:annotation";

    /** 演示计数最多保留的条目数：反复调用也不无界增长（真实业务应换带上限/TTL 的缓存） */
    private static final int MAX_TRACKED_KEYS = 1000;

    /** 键：namespace + idempotencyKey（超过上限按最久未访问淘汰） */
    private static final Map<String, AtomicInteger> SIDE_EFFECTS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                /** LRU 缓存满时淘汰最旧条目 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

    /**
     * 注解幂等入口（{@link Idempotent} 拦截）
     */
    @Idempotent(key = "#idempotencyKey", namespace = NAMESPACE_ANNOTATION)
    public TestIdempotencyInvokeResult invokeByAnnotation(String idempotencyKey) {
        return recordSideEffect(NAMESPACE_ANNOTATION, idempotencyKey);
    }

    /**
     * 无副作用包装的纯业务逻辑，供 {@link com.yomi.boot.java.component.idempotency.IdempotencyUtils} 路径调用
     */
    public TestIdempotencyInvokeResult recordSideEffect(String namespace, String idempotencyKey) {
        String mapKey = namespace + ":" + idempotencyKey;
        int count = SIDE_EFFECTS.computeIfAbsent(mapKey, k -> new AtomicInteger(0)).incrementAndGet();
        return new TestIdempotencyInvokeResult(IdUtils.nextId(), count);
    }

}
