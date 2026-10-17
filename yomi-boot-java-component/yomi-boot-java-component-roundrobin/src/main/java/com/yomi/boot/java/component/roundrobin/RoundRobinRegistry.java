package com.yomi.boot.java.component.roundrobin;

import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 轮询任务注册表（线程安全）
 * <p>
 * 启动期由各组件在 {@code @Bean} 方法里注册（refresh 阶段，早于 lifecycle 的 {@code start()}）；
 * <b>启动之后</b>注册（ApplicationRunner、懒加载 Bean、动态任务）会经 {@code lateRegistrationHandler}
 * 交给 {@link com.yomi.boot.java.component.roundrobin.config.RoundRobinLifecycle} 立刻补建调度，
 * 使注册成功即进入调度。
 *
 * @author zwei
 */
public class RoundRobinRegistry {

    /** 注册顺序不参与语义，用并发容器保证运行期注册的安全 */
    private final Map<String, RoundRobinTask> tasks = new ConcurrentHashMap<>();

    /** 启动后注册的补建回调：由 lifecycle 在 start 时设置、stop 时清空
     */
    @Setter
    private volatile Consumer<RoundRobinTask> lateRegistrationHandler;

    /**
     * 注册任务；任务定义已在创建时完成校验，这里只做同名去重
     *
     * @return 注册的任务，便于 {@code @Bean} 方法直接返回
     */
    public RoundRobinTask register(RoundRobinTask task) {
        String name = task.getName();
        // putIfAbsent 原子占位：并发同名注册只有一个成功
        if (tasks.putIfAbsent(name, task) != null) {
            throw new IllegalStateException("yomi.roundrobin 任务 name 重复: " + name);
        }
        Consumer<RoundRobinTask> handler = lateRegistrationHandler;
        if (handler != null) {
            handler.accept(task);
        }
        return task;
    }

    /** 返回已注册任务的副本列表 */
    public List<RoundRobinTask> list() {
        return new ArrayList<>(tasks.values());
    }

    /** 是否已注册指定名称的任务 */
    public boolean contains(String name) {
        return tasks.containsKey(name);
    }

}
