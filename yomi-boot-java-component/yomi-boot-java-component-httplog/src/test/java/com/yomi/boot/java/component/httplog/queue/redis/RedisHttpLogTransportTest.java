package com.yomi.boot.java.component.httplog.queue.redis;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * redis 传输 list 队列上限测试：超限丢队首最旧、未超限不动队列、0=不限、stream 不在本键作用域
 *
 * @author zwei
 */
class RedisHttpLogTransportTest {

    @Test
    void writeBatch_overLimit_trimsOldestAndCountsDropped() {
        List<String> trims = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisHttpLogTransport transport = transport(5, HttpLogProperties.RedisStructure.LIST,
                listTemplate(10, trims), registry);

        transport.writeBatch(records(10));

        assertEquals(List.of("-5,-1"), trims);
        assertEquals(5.0, dropped(registry), 0.0);
    }

    @Test
    void writeBatch_atLimit_doesNotTrim() {
        List<String> trims = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisHttpLogTransport transport = transport(5, HttpLogProperties.RedisStructure.LIST,
                listTemplate(5, trims), registry);

        transport.writeBatch(records(5));

        assertTrue(trims.isEmpty());
        assertEquals(0.0, dropped(registry), 0.0);
    }

    @Test
    void writeBatch_maxLengthZero_neverTrims() {
        List<String> trims = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisHttpLogTransport transport = transport(0, HttpLogProperties.RedisStructure.LIST,
                listTemplate(1_000_000, trims), registry);

        transport.writeBatch(records(3));

        assertTrue(trims.isEmpty());
        assertEquals(0.0, dropped(registry), 0.0);
    }

    @Test
    void writeBatch_streamStructure_ignoresMaxLength() {
        List<Integer> addArgCounts = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisHttpLogTransport transport = transport(5, HttpLogProperties.RedisStructure.STREAM,
                streamTemplate(addArgCounts), registry);

        transport.writeBatch(records(2));

        // 本键仅作用于 list：stream 分支按原样逐条 XADD（不带 MAXLEN），上限能力单独立项
        assertEquals(List.of(1, 1), addArgCounts);
        assertEquals(0.0, dropped(registry), 0.0);
    }

    /** list 结构模板：rightPushAll 返回预设的写入后长度，捕获 trim 参数 */
    private static StringRedisTemplate listTemplate(long lengthAfterPush, List<String> trims) {
        return new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public ListOperations<String, String> opsForList() {
                return (ListOperations<String, String>) Proxy.newProxyInstance(
                        ListOperations.class.getClassLoader(), new Class<?>[]{ListOperations.class},
                        (proxy, method, args) -> {
                            if ("rightPushAll".equals(method.getName())) {
                                return lengthAfterPush;
                            }
                            if ("trim".equals(method.getName())) {
                                trims.add(args[1] + "," + args[2]);
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
            }
        };
    }

    /** stream 结构模板：捕获 add 的参数个数（1=未携带 XAddOptions） */
    private static StringRedisTemplate streamTemplate(List<Integer> addArgCounts) {
        return new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <HK, HV> StreamOperations<String, HK, HV> opsForStream() {
                return (StreamOperations<String, HK, HV>) Proxy.newProxyInstance(
                        StreamOperations.class.getClassLoader(), new Class<?>[]{StreamOperations.class},
                        (proxy, method, args) -> {
                            if ("add".equals(method.getName())) {
                                addArgCounts.add(args.length);
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
            }
        };
    }

    private static RedisHttpLogTransport transport(int maxLength, HttpLogProperties.RedisStructure structure,
                                                   StringRedisTemplate template, MeterRegistry registry) {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setServiceName("test");
        properties.getRedis().setStructure(structure);
        properties.getRedis().setMaxLength(maxLength);
        HttpLogMetrics metrics = new HttpLogMetrics(
                new StaticListableBeanFactory(Map.of("meterRegistry", registry))
                        .getBeanProvider(MeterRegistry.class));
        HttpLogProperties.Desensitize desensitize = new HttpLogProperties.Desensitize();
        desensitize.setKeyTypes(Map.of());
        return new RedisHttpLogTransport(properties, new HttpLogKeys(properties, null), template,
                new HttpLogDesensitizer((type, value) -> value, desensitize), metrics);
    }

    /** redis-queue-limit 丢弃计数（未注册过按 0 计） */
    private static double dropped(MeterRegistry registry) {
        Counter counter = registry.find("yomi.httplog.dropped").tag("reason", "redis-queue-limit").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static List<HttpLogRecord> records(int count) {
        List<HttpLogRecord> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(HttpLogRecord.builder().id((long) i).uri("/" + i).build());
        }
        return list;
    }

}
