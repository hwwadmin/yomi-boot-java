package com.yomi.boot.java.component.roundrobin.spring;

import com.yomi.boot.java.component.roundrobin.RoundRobin;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinTaskDefinition;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.runtime.CancellationToken;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 启动 / 停止所有轮询任务
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RoundRobinLifecycle implements SmartLifecycle {

    /** 已注册的 @RoundRobinTask */
    private final RoundRobinRegistry registry;
    /** 任务互斥用的分布式锁客户端 */
    private final RoundRobinLockClient lockClient;

    /** 锁检测与 max-hold 调度 */
    private final ScheduledExecutorService scheduler;

    /** 调度取消令牌；stop 后 start 时会重建 */
    private CancellationToken scheduleToken = new CancellationToken();

    /** 各任务的工作线程 */
    private final List<RoundRobinWorker> workers = new ArrayList<>();

    /** 是否已 start */
    private volatile boolean running;

    /** 已建 worker 的任务名：快照启动与"运行期注册补建"两条路径都经它去重 */
    private final Set<String> startedNames = new HashSet<>();

    /** 为每个注册任务启动 Worker */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (scheduleToken.isCancelled()) {
            // 生命周期重启时新建取消令牌，以便 worker 继续运行
            scheduleToken = new CancellationToken();
        }
        // 先挂回调再取快照：快照之后发生的注册都会经回调补建，不会落在两条路径之间漏掉
        registry.setLateRegistrationHandler(this::startLateTask);
        List<RoundRobinTaskDefinition> definitions = registry.list();
        if (definitions.isEmpty()) {
            log.info("yomi.roundrobin 已启用，当前无 @RoundRobinTask");
        } else {
            for (RoundRobinTaskDefinition definition : definitions) {
                startWorker(definition, false);
            }
            log.info("yomi.roundrobin 已启动 {} 个任务，调度线程池: {}", workers.size(),
                    scheduler.getClass().getName());
        }
        running = true;
    }

    /** 建 worker（同名任务只建一次：快照与运行期注册可能同时命中同一个任务） */
    private void startWorker(RoundRobinTaskDefinition definition, boolean lateRegistration) {
        var config = definition.getConfig();
        if (!startedNames.add(config.getName())) {
            return;
        }
        log.info("RoundRobin[{}] 生效配置: {}", config.getName(), config.toLogSummary());
        RoundRobinWorker worker = RoundRobin.startWorker(scheduleToken, config, definition.getTask(),
                lockClient, scheduler);
        workers.add(worker);
        if (lateRegistration) {
            log.info("RoundRobin[{}] 运行期注册，已增量启动（历史缺陷：这类任务此前会静默不执行）", config.getName());
        }
    }

    /**
     * 启动完成后注册的任务：立刻补建调度（与 {@link RoundRobinRegistry} 的回调约定）
     * <p>同步方法与 start/stop 共用生命周期锁：注册方会阻塞到启动/停止收尾，注册成功即完成调度补建</p>
     */
    synchronized void startLateTask(RoundRobinTaskDefinition definition) {
        if (!running) {
            log.warn("RoundRobin[{}] 在未启动或已停止时注册：本次不建调度，随下次 start 生效",
                    definition.getConfig().getName());
            return;
        }
        startWorker(definition, true);
    }

    /** 停止全部 Worker */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        registry.setLateRegistrationHandler(null);
        scheduleToken.cancel();
        List<Thread> joiners = new ArrayList<>(workers.size());
        for (RoundRobinWorker worker : workers) {
            Duration await = worker.shutdownAwait();
            joiners.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    worker.awaitStop(await);
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
        workers.clear();
        startedNames.clear();
        running = false;
        log.info("yomi.roundrobin 已停止");
    }

    /** 是否仍有任务在运行 */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** 尽量晚启动、早停止的 SmartLifecycle 阶段 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

}
