package com.yomi.boot.java.component.httplog.queue.memory;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.HttpLogBatchHandler;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * memory 模式传输：进程内有界缓冲 + 本地消费线程批量落库
 * <p>跳数最少；进程崩溃会丢失缓冲内与在途批次的日志（允许丢，见组件文档丢失边界表）。
 *
 * @author zwei
 */
@Slf4j
public class MemoryHttpLogTransport implements HttpLogSink, HttpLogConsumer {

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 入队丢弃/失败等指标 */
    private final HttpLogMetrics metrics;

    /** 后台补脱敏（in-request-thread=false 时生效） */
    private final HttpLogDesensitizer desensitizer;

    /** 有界内存队列 */
    private final BlockingQueue<HttpLogRecord> queue;

    /** 后台 worker 是否在跑 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 是否有在途批次（flush 等待依据） */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** 阻塞拉取队列并交给 sink 的线程 */
    private volatile Thread worker;

    public MemoryHttpLogTransport(HttpLogProperties properties, HttpLogMetrics metrics,
                                  HttpLogDesensitizer desensitizer) {
        this.properties = properties;
        this.metrics = metrics;
        this.desensitizer = desensitizer;
        this.queue = new ArrayBlockingQueue<>(properties.getBuffer().getCapacity());
        metrics.registerQueueDepth("memory", queue);
    }

    /** 非阻塞入队，满则丢弃 */
    @Override
    public boolean offer(HttpLogRecord record) {
        if (queue.offer(record)) {
            return true;
        }
        if (properties.getBuffer().getDropPolicy() == HttpLogProperties.DropPolicy.DROP_OLD) {
            queue.poll();
            return queue.offer(record);
        }
        return false;
    }

    /** 启动 worker 线程 */
    @Override
    public void start(HttpLogBatchHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofPlatform().name("httplog-memory-consumer").daemon(true)
                .start(() -> consumeLoop(handler));
        log.info("[httplog]memory 模式消费线程已启动: capacity={}, batchSize={}, flushInterval={}",
                properties.getBuffer().getCapacity(), properties.getBuffer().getBatchSize(),
                properties.getBuffer().getFlushInterval());
    }

    /** 通知 worker 退出并 join */
    @Override
    public void stop(Duration timeout) {
        // 先排空（消费线程存活期间），再停线程
        flush(timeout);
        running.set(false);
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            try {
                current.join(Math.max(100L, timeout.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 等待队列被消费至空（带超时） */
    @Override
    public void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && !inFlight.get()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = queue.size();
        if (remaining > 0) {
            metrics.dropped("shutdown-timeout", remaining);
        }
    }

    /** 消费循环：首条阻塞等待，随后 drainTo 攒批；批次处理失败即丢弃计数（重试在 store 内） */
    private void consumeLoop(HttpLogBatchHandler handler) {
        HttpLogProperties.Buffer buffer = properties.getBuffer();
        int batchSize = buffer.getBatchSize();
        long flushMillis = buffer.getFlushInterval().toMillis();
        List<HttpLogRecord> batch = new ArrayList<>(Math.min(batchSize, 1024));
        while (running.get()) {
            try {
                HttpLogRecord first = queue.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                inFlight.set(true);
                try {
                    // 后台补脱敏：发送/落库前统一处理（in-request-thread=true 时内部直接返回）
                    for (HttpLogRecord record : batch) {
                        desensitizer.maskDeferredBodies(record);
                    }
                    handler.onBatch(batch);
                } catch (Exception e) {
                    metrics.failed("consume", e);
                    metrics.dropped("consume-failed", batch.size());
                } finally {
                    batch.clear();
                    inFlight.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                metrics.failed("memory-loop", t);
            }
        }
    }

}
