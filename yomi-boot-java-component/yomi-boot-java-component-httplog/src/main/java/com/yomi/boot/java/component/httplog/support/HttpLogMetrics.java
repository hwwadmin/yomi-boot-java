package com.yomi.boot.java.component.httplog.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组件指标与限流日志：written / dropped / failed + 队列深度
 * <p>无 MeterRegistry 时仅保留限流日志，不影响功能。
 *
 * @author zwei
 */
@Slf4j
public class HttpLogMetrics {

    /** 同类告警最小间隔 */
    private static final long WARN_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    /** 指标注册表（无则用空实现） */
    private final MeterRegistry registry;

    /** 上次打印丢弃告警的时间戳 */
    private final AtomicLong lastDropWarnAt = new AtomicLong(0);

    /** 上次打印失败告警的时间戳 */
    private final AtomicLong lastFailWarnAt = new AtomicLong(0);

    public HttpLogMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    /** 成功写入 n 条 */
    public void written(int n) {
        counter("yomi.httplog.written", null).increment(n);
    }

    /** 丢弃 n 条（reason：queue-full / shutdown-timeout / consume-failed / redis-queue-limit） */
    public void dropped(String reason, int n) {
        counter("yomi.httplog.dropped", reason).increment(n);
        if (warnThrottled(lastDropWarnAt)) {
            log.warn("[httplog]日志丢弃 count={}, reason={}（持续丢弃通常为落库能力不足：检查 DB 批插耗时/批量参数）",
                    n, reason);
        }
    }

    /** 处理失败计数 + 限流日志 */
    public void failed(String stage, Throwable t) {
        counter("yomi.httplog.failed", stage).increment();
        if (warnThrottled(lastFailWarnAt)) {
            if (t == null) {
                log.error("[httplog]处理失败 stage={}", stage);
            } else {
                log.error("[httplog]处理失败 stage={}", stage, t);
            }
        }
    }

    /** 注册队列深度（mode 维度），供监控观察缓冲水位 */
    public void registerQueueDepth(String mode, Queue<?> queue) {
        if (registry == null) {
            return;
        }
        try {
            Gauge.builder("yomi.httplog.queue.depth", queue, Queue::size)
                    .tag("mode", mode)
                    .register(registry);
        } catch (Exception e) {
            log.debug("[httplog]注册队列深度指标失败: {}", e.getMessage());
        }
    }

    /** 按名称获取或创建 Counter */
    private Counter counter(String name, String reason) {
        if (registry == null) {
            return NOOP;
        }
        var builder = Counter.builder(name);
        if (reason != null) {
            builder.tag("reason", reason);
        }
        return builder.register(registry);
    }

    /** 限流：同类告警间隔不小于 WARN_INTERVAL_NANOS */
    private static boolean warnThrottled(AtomicLong lastAt) {
        long now = System.nanoTime();
        long last = lastAt.get();
        return now - last >= WARN_INTERVAL_NANOS && lastAt.compareAndSet(last, now);
    }

    /** 无 registry 时的空实现 */
    private static final Counter NOOP = new Counter() {
        /** 计数器加一 */
        @Override
        public void increment(double amount) {
        }

        /** 读取计数器当前值 */
        @Override
        public double count() {
            return 0;
        }

        /** 指标注册用的稳定 id */
        @Override
        public Id getId() {
            return null;
        }
    };

}
