package com.yomi.boot.java.component.roundrobin;

import java.time.Duration;

/**
 * 轮询任务创建统一入口
 * <p>
 * 创建出的 {@link RoundRobinTask} 交给 {@link RoundRobinRegistry#register} 注册。
 *
 * @author zwei
 */
public final class RoundRobin {

    private RoundRobin() {
    }

    /** 创建轮询任务（不填简介）：任务名、最小间隔、单轮执行体 */
    public static RoundRobinTask task(String name, Duration interval, RoundRobinHandler handler) {
        return RoundRobinTask.builder()
                .name(name)
                .interval(interval)
                .handler(handler)
                .build();
    }

    /** 创建轮询任务：任务名、作用简介（启动日志）、最小间隔、单轮执行体 */
    public static RoundRobinTask task(String name, String description, Duration interval, RoundRobinHandler handler) {
        return RoundRobinTask.builder()
                .name(name)
                .description(description)
                .interval(interval)
                .handler(handler)
                .build();
    }

    /** 需要高级参数（execLockTtl / maxHoldDuration / lockWait / shutdownAwait）时用 builder 创建 */
    public static RoundRobinTask.RoundRobinTaskBuilder task(String name, Duration interval) {
        return RoundRobinTask.builder().name(name).interval(interval);
    }

}
