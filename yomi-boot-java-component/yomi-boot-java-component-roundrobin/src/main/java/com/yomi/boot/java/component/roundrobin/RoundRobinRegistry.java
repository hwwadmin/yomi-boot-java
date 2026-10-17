package com.yomi.boot.java.component.roundrobin;

import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.runtime.CancellationToken;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinLoop;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinTick;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轮询任务注册表：注册即登记；组件启动后为每个任务创建调度实例，运行期注册的任务立即补建实例；
 * 容器关闭时统一停掉所有实例。
 * <p>
 * 组件启动由 {@link SmartLifecycle} 承担（phase = MAX-200，refresh 收尾）：晚于所有单例创建与启动期初始化
 * （如各组件建表），早于 ApplicationRunner；注册与启停共用同一把锁，"注册 → 建实例"没有中间窗口。
 * <p>
 * 注册时机：组件与宿主在 {@code @Bean} 方法里注册（refresh 期），实例随组件启动统一创建；
 * 启动之后注册（ApplicationRunner、懒加载 Bean、动态任务）会立即创建实例——注册成功即进入调度。
 *
 * @author zwei
 */
@Slf4j
public class RoundRobinRegistry implements SmartLifecycle {

    /** 已登记任务（注册顺序不参与语义） */
    private final Map<String, RoundRobinTask> tasks = new ConcurrentHashMap<>();

    /** 各任务的调度实例（启动快照建立 / 运行期注册补建） */
    private final Map<String, RoundRobinWorker> instances = new ConcurrentHashMap<>();

    /** 任务互斥用的分布式锁客户端 */
    private final RoundRobinLockClient lockClient;

    /** 调度取消令牌；stop 后 start 时会重建 */
    private CancellationToken scheduleToken = new CancellationToken();

    /** 是否已启动 */
    private volatile boolean running;

    public RoundRobinRegistry(RoundRobinLockClient lockClient) {
        this.lockClient = lockClient;
    }

    /**
     * 注册任务（同名注册只允许一次）；组件已启动时立即创建调度实例
     *
     * @return 注册的任务，便于 {@code @Bean} 方法直接返回
     */
    public synchronized RoundRobinTask register(RoundRobinTask task) {
        String name = task.getName();
        // putIfAbsent 原子占位：并发同名注册只有一个成功
        if (tasks.putIfAbsent(name, task) != null) {
            throw new IllegalStateException("yomi.roundrobin 任务 name 重复: " + name);
        }
        if (running) {
            startInstance(task);
        }
        return task;
    }

    /** 返回已登记任务的副本列表 */
    public List<RoundRobinTask> list() {
        return new ArrayList<>(tasks.values());
    }

    /** 是否已登记指定名称的任务 */
    public boolean contains(String name) {
        return tasks.containsKey(name);
    }

    /**
     * 为所有已登记任务创建调度实例；此后注册的任务由 {@link #register} 立即补建
     * <p>启动日志（task started + 生效配置）由实例线程输出一行，本方法只打汇总</p>
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (scheduleToken.isCancelled()) {
            // 生命周期重启时新建取消令牌，以便实例继续运行
            scheduleToken = new CancellationToken();
        }
        for (RoundRobinTask task : tasks.values()) {
            startInstance(task);
        }
        running = true;
        if (instances.isEmpty()) {
            log.info("yomi.roundrobin 已启用，当前无已注册的轮询任务");
        } else {
            log.info("yomi.roundrobin 已启动 {} 个任务", instances.size());
        }
    }

    /** 停止全部调度实例 */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        scheduleToken.cancel();
        List<Thread> joiners = new ArrayList<>(instances.size());
        for (RoundRobinWorker instance : instances.values()) {
            Duration await = instance.shutdownAwait();
            joiners.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    instance.awaitStop(await);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (Thread joiner : joiners) {
            joiner.start();
        }
        for (Thread joiner : joiners) {
            try {
                joiner.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        instances.clear();
        running = false;
        log.info("yomi.roundrobin 已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 尽量晚启动、早停止的 SmartLifecycle 阶段 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    /** 建实例并纳入停机回收（调用方持有监视器，同名任务只会建一次） */
    private void startInstance(RoundRobinTask task) {
        // 取本轮快照：实例线程体内不再读可变字段（stop→start 会换新令牌）
        CancellationToken token = scheduleToken;
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(lockClient));
        Thread thread = Thread.ofVirtual()
                .name("roundrobin-" + task.getName())
                .unstarted(() -> loop.run(token, task));
        thread.start();
        instances.put(task.getName(), new RoundRobinWorker(task, thread));
    }

}
