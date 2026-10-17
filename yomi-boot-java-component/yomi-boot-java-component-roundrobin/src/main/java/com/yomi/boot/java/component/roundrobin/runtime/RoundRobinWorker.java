package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 任务的调度实例：一条虚拟线程（自驱轮询循环）+ 对应的任务定义，
 * 由 {@link com.yomi.boot.java.component.roundrobin.RoundRobinRegistry} 创建并在容器关闭时回收
 *
 * @author zwei
 */
@Slf4j
public final class RoundRobinWorker {

    /** 对应的任务定义 */
    private final RoundRobinTask task;

    /** 轮询循环线程 */
    private final Thread thread;

    public RoundRobinWorker(RoundRobinTask task, Thread thread) {
        this.task = task;
        this.thread = thread;
    }

    /** 任务名 */
    public String name() {
        return task.getName();
    }

    /** 对应的任务定义 */
    public RoundRobinTask task() {
        return task;
    }

    /** 本实例停止时等待线程结束的超时 */
    public Duration shutdownAwait() {
        return task.getShutdownAwait();
    }

    /**
     * 等待工作线程结束，超时后不中断线程。
     * <p>超时说明任务没响应取消标志（协作式停机）：这里留一条 WARN——停机后它仍在运行，
     * 同 JVM 重启（stop→start）时旧线程与新实例可能并发执行同一任务</p>
     */
    public void awaitStop(Duration timeout) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            return;
        }
        thread.join(timeout.toMillis());
        if (thread.isAlive()) {
            log.warn("RoundRobin 工作线程 {} 在 {} 内未结束（任务未响应取消标志，仍在运行）：停机后它可能继续执行，"
                    + "同 JVM 重启时旧线程与新实例可能并发执行同一任务；"
                    + "请让 handler 轮询 ctx.isCancelled()，或调大 shutdown-await", thread.getName(), timeout);
        }
    }

}
