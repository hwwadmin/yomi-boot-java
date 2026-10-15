package com.yomi.boot.java.component.httplog.queue.memory;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * memory 传输测试：有界缓冲丢弃策略与批量消费
 *
 * @author zwei
 */
class MemoryHttpLogTransportTest {

    @Test
    void offer_capacityFull_dropsNew() {
        MemoryHttpLogTransport transport = transport(2, 10, HttpLogProperties.DropPolicy.DROP_NEW);
        assertTrue(transport.offer(record("/1")));
        assertTrue(transport.offer(record("/2")));
        assertFalse(transport.offer(record("/3")));
    }

    @Test
    void offer_dropOld_keepsLatest() throws Exception {
        MemoryHttpLogTransport transport = transport(2, 10, HttpLogProperties.DropPolicy.DROP_OLD);
        List<HttpLogRecord> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstBatchArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 消费者被 handler 卡住：offer 期间队列状态完全由主线程控制（避免消费竞态）
        transport.start(batch -> {
            received.addAll(batch);
            firstBatchArrived.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        transport.offer(record("/1"));
        assertTrue(firstBatchArrived.await(3, TimeUnit.SECONDS), "消费者未取走首条");

        transport.offer(record("/2"));
        transport.offer(record("/3"));
        // 容量 2 已满，drop-old 挤掉队首 /2
        assertTrue(transport.offer(record("/4")));

        release.countDown();
        transport.stop(Duration.ofSeconds(1));
        List<String> uris = received.stream().map(HttpLogRecord::getUri).toList();
        assertEquals(List.of("/1", "/3", "/4"), uris);
    }

    @Test
    void consumer_batchesAndHandles() throws Exception {
        MemoryHttpLogTransport transport = transport(64, 3, HttpLogProperties.DropPolicy.DROP_NEW);
        List<HttpLogRecord> received = collectFirstBatch(transport, 3);
        assertEquals(3, received.size());
    }

    @Test
    void flush_emptyBuffer_returnsImmediately() {
        MemoryHttpLogTransport transport = transport(8, 2, HttpLogProperties.DropPolicy.DROP_NEW);
        long start = System.nanoTime();
        transport.flush(Duration.ofSeconds(1));
        assertTrue(System.nanoTime() - start < Duration.ofMillis(500).toNanos());
    }

    /** 启动消费并收集首批记录 */
    private static List<HttpLogRecord> collectFirstBatch(MemoryHttpLogTransport transport, int count)
            throws InterruptedException {
        List<HttpLogRecord> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        transport.start(batch -> {
            received.addAll(batch);
            latch.countDown();
        });
        for (int i = 1; i <= count; i++) {
            transport.offer(record("/" + i));
        }
        assertTrue(latch.await(3, TimeUnit.SECONDS), "消费线程未在 3 秒内处理首批");
        transport.stop(Duration.ofSeconds(1));
        return new ArrayList<>(received);
    }

    private static MemoryHttpLogTransport transport(int capacity, int batchSize,
                                                    HttpLogProperties.DropPolicy policy) {
        HttpLogProperties properties = new HttpLogProperties();
        properties.getBuffer().setCapacity(capacity);
        properties.getBuffer().setBatchSize(batchSize);
        properties.getBuffer().setFlushInterval(Duration.ofMillis(50));
        properties.getBuffer().setDropPolicy(policy);
        return new MemoryHttpLogTransport(properties, metrics(), desensitizer());
    }

    private static HttpLogMetrics metrics() {
        return new HttpLogMetrics(new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
    }

    private static HttpLogDesensitizer desensitizer() {
        HttpLogProperties.Desensitize desensitize = new HttpLogProperties.Desensitize();
        desensitize.setKeyTypes(Map.of());
        return new HttpLogDesensitizer((type, value) -> value, desensitize);
    }

    private static HttpLogRecord record(String uri) {
        return HttpLogRecord.builder().id(1L).uri(uri).build();
    }

}
