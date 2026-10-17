package com.yomi.boot.java.component.httplog.config;

import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.store.HttpLogStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;

/**
 * 组件生命周期：启动消费循环（批量落库），停机先排空缓冲再停消费；
 * phase 取 MAX-4096（低于 Boot 的 graceful-shutdown 与 server-stop）——
 * 启动早于容器开始收请求，停机晚于容器停止收请求，在途请求的日志一并排空，且仍早于数据源等 Bean 销毁。
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class HttpLogLifecycle implements SmartLifecycle {

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 日志落库或外部存储的实现 */
    private final HttpLogSink sink;

    /** 从传输层拉取并交给 sink 的消费循环 */
    private final HttpLogConsumer consumer;

    /** 存储实现 */
    private final HttpLogStore store;

    /** 消费线程是否在运行 */
    private volatile boolean running;

    /** 启动传输与消费者线程 */
    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        consumer.start(this::handleBatch);
        log.info("[httplog]消费已启动: mode={}", properties.getMode());
    }

    /** SmartLifecycle 停止：排空缓冲后停止消费与发送线程 */
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        Duration drainTimeout = properties.getBuffer().getShutdownDrainTimeout();
        sink.flush(drainTimeout);
        consumer.stop(drainTimeout);
        log.info("[httplog]消费已停止");
    }

    /** DisposableBean 销毁时同样停止后台线程 */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /** 消费循环是否仍在运行 */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** 早于 Web 容器启动、晚于 Web 容器停止、早于 Bean 销毁的阶段 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 4096;
    }

    /** 批量落库 */
    private void handleBatch(List<HttpLogRecord> batch) {
        store.writeBatch(batch);
    }

}
