package com.yomi.boot.java.component.roundrobin.runtime;

import java.time.Duration;

/**
 * 后台轮询工作线程句柄，由 {@link com.yomi.boot.java.component.roundrobin.RoundRobin#startWorker} 创建
 *
 * @author zwei
 */
public final class RoundRobinWorker {

    /** 轮询循环线程 */
    private final Thread thread;

    /** stop 时 join 超时 */
    private final Duration shutdownAwait;

    public RoundRobinWorker(Thread thread, Duration shutdownAwait) {
        this.thread = thread;
        this.shutdownAwait = shutdownAwait;
    }

    /** 请求停止并等待线程结束 */
    public Duration shutdownAwait() {
        return shutdownAwait;
    }

    /**
     * 等待工作线程结束，超时后不中断线程
     */
    public void awaitStop(Duration timeout) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            return;
        }
        thread.join(timeout.toMillis());
    }

}
