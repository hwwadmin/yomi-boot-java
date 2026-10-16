package com.yomi.boot.java.component.httplog.queue.memory;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.AbstractBufferedTransport;
import com.yomi.boot.java.component.httplog.queue.HttpLogBatchHandler;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * memory 模式传输：进程内有界缓冲 + 本地消费线程批量落库
 * <p>跳数最少；进程崩溃会丢失缓冲内与在途批次的日志（允许丢，见组件文档丢失边界表）。
 *
 * @author zwei
 */
@Slf4j
public class MemoryHttpLogTransport extends AbstractBufferedTransport implements HttpLogSink, HttpLogConsumer {

    /** 批量落库回调（start 时注入） */
    private volatile HttpLogBatchHandler handler;

    /** 阻塞拉取队列并落库的线程 */
    private volatile Thread worker;

    public MemoryHttpLogTransport(HttpLogProperties properties, HttpLogMetrics metrics,
                                  HttpLogDesensitizer desensitizer) {
        super("memory", properties, metrics, desensitizer);
    }

    /** 启动 worker 线程 */
    @Override
    public void start(HttpLogBatchHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.handler = handler;
        worker = startLoopThread("httplog-memory-consumer", "consume", "consume-failed", "memory-loop");
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
        stopThread(worker, timeout);
    }

    @Override
    protected void writeBatch(List<HttpLogRecord> batch) {
        handler.onBatch(batch);
    }

}
