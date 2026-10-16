package com.yomi.boot.java.component.httplog.queue;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界缓冲传输骨架：入队丢弃策略、攒批循环、flush 与停机 join 统一在此实现
 * <p>子类只负责介质差异：{@link #writeBatch} 把一批记录写出去，以及 start/stop 的线程与容器编排。
 * 指标标签由子类在 {@link #startLoopThread} 传入（"memory" 与推送型介质的失败/丢弃标签不同，属既有观测口径）。
 *
 * @author zwei
 */
public abstract class AbstractBufferedTransport {

    /** 模块配置 */
    protected final HttpLogProperties properties;

    /** 队列深度、丢弃与失败指标 */
    protected final HttpLogMetrics metrics;

    /** L1 有界缓冲 */
    protected final BlockingQueue<HttpLogRecord> buffer;

    /** 发送/消费线程是否在运行 */
    protected final AtomicBoolean running = new AtomicBoolean(false);

    /** 发送前补脱敏（in-request-thread=true 时内部直接返回） */
    private final HttpLogDesensitizer desensitizer;

    /** 是否有在途批次（flush 等待依据） */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    protected AbstractBufferedTransport(String queueName, HttpLogProperties properties,
                                        HttpLogMetrics metrics, HttpLogDesensitizer desensitizer) {
        this.properties = properties;
        this.metrics = metrics;
        this.desensitizer = desensitizer;
        this.buffer = new ArrayBlockingQueue<>(properties.getBuffer().getCapacity());
        metrics.registerQueueDepth(queueName, buffer);
    }

    /** 非阻塞入队：满时按丢弃策略处理 */
    public boolean offer(HttpLogRecord record) {
        if (buffer.offer(record)) {
            return true;
        }
        if (properties.getBuffer().getDropPolicy() == HttpLogProperties.DropPolicy.DROP_OLD) {
            buffer.poll();
            return buffer.offer(record);
        }
        return false;
    }

    /** 等待缓冲被消费至空（带超时），超时剩余按丢弃计数 */
    public void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (buffer.isEmpty() && !inFlight.get()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = buffer.size();
        if (remaining > 0) {
            metrics.dropped("shutdown-timeout", remaining);
        }
    }

    /** 中断并 join 线程（join 超时下限 100ms），供子类 stop 复用 */
    protected void stopThread(Thread thread, Duration timeout) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Math.max(100L, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 起一条批量循环线程 */
    protected Thread startLoopThread(String threadName, String failStage, String dropStage, String loopStage) {
        return Thread.ofPlatform().name(threadName).daemon(true)
                .start(() -> sendLoop(failStage, dropStage, loopStage));
    }

    /**
     * 发送循环：首条阻塞等待，随后 drainTo 攒批 → 补脱敏 → {@link #writeBatch}
     * <p>批次处理失败即丢弃计数（重试在介质侧/store 内），绝不让毒批阻塞循环
     */
    private void sendLoop(String failStage, String dropStage, String loopStage) {
        HttpLogProperties.Buffer bufferConfig = properties.getBuffer();
        int batchSize = bufferConfig.getBatchSize();
        long flushMillis = bufferConfig.getFlushInterval().toMillis();
        List<HttpLogRecord> batch = new ArrayList<>(Math.min(batchSize, 1024));
        while (running.get()) {
            try {
                HttpLogRecord first = buffer.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                buffer.drainTo(batch, batchSize - 1);
                inFlight.set(true);
                try {
                    // 补脱敏：写入/落库前统一处理（in-request-thread=true 时内部直接返回）
                    for (HttpLogRecord record : batch) {
                        desensitizer.maskDeferredBodies(record);
                    }
                    writeBatch(batch);
                } catch (Exception e) {
                    metrics.failed(failStage, e);
                    metrics.dropped(dropStage, batch.size());
                } finally {
                    batch.clear();
                    inFlight.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                metrics.failed(loopStage, t);
            }
        }
    }

    /** 子类实现：把一批（已补脱敏的）记录写到目标介质 */
    protected abstract void writeBatch(List<HttpLogRecord> batch);

}
