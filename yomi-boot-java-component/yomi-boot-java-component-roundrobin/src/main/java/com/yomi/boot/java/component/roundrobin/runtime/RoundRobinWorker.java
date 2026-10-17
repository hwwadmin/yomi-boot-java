package com.yomi.boot.java.component.roundrobin.runtime;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 后台轮询工作线程句柄，由 {@link com.yomi.boot.java.component.roundrobin.RoundRobin#startWorker} 创建
 *
 * @author zwei
 */
@Slf4j
public final class RoundRobinWorker {

    /** 轮询循环线程 */
    private final Thread thread;

    /** stop 时 join 超时 */
    private final Duration shutdownAwait;

    public RoundRobinWorker(Thread thread, Duration shutdownAwait) {
        this.thread = thread;
        this.shutdownAwait = shutdownAwait;
    }

    /** 本 worker 停止时等待线程结束的超时 */
    public Duration shutdownAwait() {
        return shutdownAwait;
    }

    /**
     * 等待工作线程结束，超时后不中断线程。
     * <p>超时说明任务没响应取消标志（协作式停机）：这里留一条 WARN——停机后它仍在运行，
     * 同 JVM 重启（stop→start）时旧线程与新 worker 可能并发执行同一任务</p>
     */
    public void awaitStop(Duration timeout) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            return;
        }
        thread.join(timeout.toMillis());
        if (thread.isAlive()) {
            log.warn("RoundRobin 工作线程 {} 在 {} 内未结束（任务未响应取消标志，仍在运行）：停机后它可能继续执行，"
                    + "同 JVM 重启时旧线程与新 worker 可能并发执行同一任务；"
                    + "请让 handler 轮询 ctx.isCancelled()，或调大 shutdown-await", thread.getName(), timeout);
        }
    }

}
