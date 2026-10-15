package com.yomi.boot.java.component.roundrobin.core;

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
 * 交给 {@link com.yomi.boot.java.component.roundrobin.spring.RoundRobinLifecycle} 立刻补建调度——
 * 否则任务注册成功但不会进入调度。
 *
 * @author zwei
 */
public class RoundRobinRegistry {

    /** 注册顺序不参与语义，用并发容器保证运行期注册的安全 */
    private final Map<String, RoundRobinTaskDefinition> tasks = new ConcurrentHashMap<>();

    /** 启动后注册的补建回调：由 lifecycle 在 start 时设置、stop 时清空 */
    private volatile Consumer<RoundRobinTaskDefinition> lateRegistrationHandler;

    /** 注册任务定义 */
    public void register(RoundRobinTaskDefinition definition) {
        String name = definition.getConfig().getName();
        definition.getConfig().validate();
        // putIfAbsent 原子占位：并发同名注册只有一个成功（原实现是 containsKey + put 的检查-写入竞态）
        if (tasks.putIfAbsent(name, definition) != null) {
            throw new IllegalStateException("yomi.roundrobin 任务 name 重复: " + name);
        }
        Consumer<RoundRobinTaskDefinition> handler = lateRegistrationHandler;
        if (handler != null) {
            handler.accept(definition);
        }
    }

    /** 由 lifecycle 设置：启动完成后注册的任务需要立即补建调度 */
    public void setLateRegistrationHandler(Consumer<RoundRobinTaskDefinition> handler) {
        this.lateRegistrationHandler = handler;
    }

    /** 返回已注册任务定义的副本列表 */
    public List<RoundRobinTaskDefinition> list() {
        return new ArrayList<>(tasks.values());
    }

    /** 是否已注册指定名称的任务 */
    public boolean contains(String name) {
        return tasks.containsKey(name);
    }

}
