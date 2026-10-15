package com.yomi.boot.java.component.roundrobin;

import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinTaskDefinition;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.runtime.*;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 轮询任务创建与启动的统一入口
 *
 * @author zwei
 */
public final class RoundRobin {

    private RoundRobin() {
    }

    /** 声明并注册轮询任务 */
    public static RoundRobinTaskDefinition task(RoundRobinConfig config, RoundRobinHandler handler) {
        return new RoundRobinTaskDefinition(config, handler);
    }

    /**
     * 将 Bean 方法适配为 {@link RoundRobinHandler}（供 {@code @RoundRobinTask} 等声明式注册使用）
     */
    public static RoundRobinHandler handler(Object target, Method method) {
        requireValidTaskMethod(method);
        return ctx -> {
            Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[]{ctx};
            ReflectionUtils.invokeMethod(method, target, args);
        };
    }

    /** 校验方法签名符合任务约定 */
    public static void requireValidTaskMethod(Method method) {
        int paramCount = method.getParameterCount();
        if (paramCount == 0) {
            return;
        }
        if (paramCount == 1 && RoundRobinContext.class.isAssignableFrom(method.getParameterTypes()[0])) {
            return;
        }
        throw new IllegalStateException("RoundRobin 任务方法参数非法: " + method);
    }

    /**
     * 在虚拟线程中后台执行轮询，直至 {@code scheduleToken} 取消
     */
    public static RoundRobinWorker startWorker(CancellationToken scheduleToken, RoundRobinConfig config,
                                             RoundRobinHandler handler, RoundRobinLockClient lockClient,
                                             ScheduledExecutorService scheduler) {
        config.validate();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(lockClient, scheduler));
        Thread thread = Thread.ofVirtual()
                .name("roundrobin-" + config.getName())
                .unstarted(() -> loop.run(scheduleToken, config, handler));
        thread.start();
        return new RoundRobinWorker(thread, config.getShutdownAwait());
    }

    /**
     * 在当前线程阻塞执行轮询
     */
    public static void runBlocking(CancellationToken scheduleToken, RoundRobinConfig config, RoundRobinHandler handler,
                                 RoundRobinLockClient lockClient, ScheduledExecutorService scheduler) {
        config.validate();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(lockClient, scheduler));
        loop.run(scheduleToken, config, handler);
    }

}
