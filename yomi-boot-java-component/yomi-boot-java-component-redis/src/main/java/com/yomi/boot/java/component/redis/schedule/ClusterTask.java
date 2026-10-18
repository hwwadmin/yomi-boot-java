package com.yomi.boot.java.component.redis.schedule;

import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.RedisUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import com.yomi.boot.java.core.utils.SpringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Objects;

/**
 * 集群定时任务：固定延迟调度 + 两把 Redis 键，集群内每个间隔最多一个实例执行
 * <p>
 * 每任务独占一个单线程调度器，任务之间不互相阻塞。两把键分工：
 * <ul>
 *   <li><b>频率占位</b> {@code yomi:schedule:{应用}:claim:{任务}}：{@code SET NX}、TTL = 间隔，
 *       拿到占位的实例才执行本轮——集群每间隔只放行一台；持有方挂死也不用干预，占位到期自动放行</li>
 *   <li><b>执行锁</b> {@code yomi:schedule:{应用}:lock:{任务}}：Redisson 看门狗租期，
 *       上一轮仍在执行时下一轮跳过，长任务不会与下一轮并发</li>
 * </ul>
 * 任一步未通过本轮静默跳过（正常情况）；任务体异常记 ERROR 后继续下一轮，调度不中断。
 * 容器停机时优雅退出：等当前一轮结束（上限 {@link #SHUTDOWN_AWAIT}）、不中断；超时后的残留线程是守护线程，
 * 不阻塞 JVM 退出。
 * <p>
 * 任务名即集群互斥维度，改名等于换键；多应用共用 Redis 时由 {@code spring.application.name} 隔离
 *
 * @author zwei
 */
@Slf4j
public class ClusterTask implements SmartLifecycle {

    /** 键前缀：yomi:schedule:{应用}:claim|lock:{任务} */
    private static final String KEY_PREFIX = "yomi:schedule:";

    /** 停机等待当前一轮结束的上限 */
    private static final Duration SHUTDOWN_AWAIT = Duration.ofSeconds(30);

    /** 频率占位的持有者标识（pid@host），仅用于排查谁占着本轮 */
    private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

    /** 任务名：日志标识与键名的一部分 */
    @Getter
    private final String taskName;

    /** 调度间隔：上一轮结束到下一轮开始 */
    @Getter
    private final Duration interval;

    /** 频率占位键；启动时解析（bean 创建期拿不到 Environment：组件装配先于 core 的 SpringUtils） */
    @Getter
    private volatile String claimKey;

    /** 执行锁键；启动时解析 */
    @Getter
    private volatile String lockKey;

    /** 单轮执行体 */
    private final Runnable action;

    /** 单线程调度器，随容器启停 */
    private volatile ThreadPoolTaskScheduler scheduler;

    /** 是否已启动 */
    private volatile boolean running;

    protected ClusterTask(String taskName, Duration interval, Runnable action) {
        this.taskName = requireTaskName(taskName);
        this.interval = requireInterval(interval);
        this.action = Objects.requireNonNull(action, "集群定时任务执行体不能为空（任务: " + taskName + "）");
    }

    /** 创建集群定时任务：任务名（互斥维度与键名）、调度间隔、单轮执行体；随容器生命周期启停，首轮立即执行 */
    public static ClusterTask of(String taskName, Duration interval, Runnable action) {
        return new ClusterTask(taskName, interval, action);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        String base = KEY_PREFIX + requireApplicationName(SpringUtils.getBean(Environment.class)) + ":";
        String claimKey = base + "claim:" + taskName;
        String lockKey = base + "lock:" + taskName;
        this.claimKey = claimKey;
        this.lockKey = lockKey;
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix(taskName + "-");
        // 优雅停机：等当前一轮跑完再退出、不中断；守护线程，超时后的残留线程不阻塞 JVM 退出（任务幂等，可安全放弃）
        taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        taskScheduler.setAwaitTerminationSeconds((int) SHUTDOWN_AWAIT.toSeconds());
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        taskScheduler.scheduleWithFixedDelay(() -> runOnce(claimKey, lockKey), interval);
        this.scheduler = taskScheduler;
        this.running = true;
        log.info("[集群定时任务]已启动: task={}, interval={}, claimKey={}, lockKey={}",
                taskName, interval, claimKey, lockKey);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        ThreadPoolTaskScheduler taskScheduler = this.scheduler;
        this.scheduler = null;
        taskScheduler.shutdown();
        log.info("[集群定时任务]已停止: task={}", taskName);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 生命周期阶段：启动尽量晚（所有单例创建、建表之后），停机尽量早（先于低阶段 Bean 停止与基础设施销毁） */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    /** 单轮：占位 → 抢执行锁 → 执行；任一步未通过本轮跳过；失败记 ERROR，下一轮继续 */
    private void runOnce(String claimKey, String lockKey) {
        try {
            if (!tryClaim(claimKey)) {
                log.debug("[集群定时任务]本轮跳过，其它实例已占本轮: task={}", taskName);
                return;
            }
            LockUtils.tryLock(lockKey, () -> {
                action.run();
                return null;
            }, "集群定时任务抢执行锁失败");
        } catch (LockBusyException e) {
            log.debug("[集群定时任务]本轮跳过，上一轮仍在执行: task={}", taskName);
        } catch (Throwable t) {
            log.error("[集群定时任务]执行失败，{} 后重试: task={}", interval, taskName, t);
        }
    }

    /**
     * 频率占位（{@code SET NX}，TTL = 间隔）：占位在集群内每间隔只放行一个实例；
     * 持有方挂死无需干预——占位到期自动放行，长任务由执行锁看门狗兜底
     * <p>可覆写方法：测试注入占位结果（沿用任务参数的注入缝做法），生产走 Redis
     */
    protected boolean tryClaim(String claimKey) {
        return Boolean.TRUE.equals(RedisUtils.stringRedisTemplate().opsForValue()
                .setIfAbsent(claimKey, INSTANCE_ID, interval));
    }

    /** 校验任务名非空（日志标识与键名的一部分） */
    private static String requireTaskName(String taskName) {
        if (StringUtils.isBlank(taskName) || !taskName.equals(taskName.trim())) {
            throw new IllegalArgumentException("集群定时任务 taskName 不能为空或含首尾空白");
        }
        return taskName;
    }

    /** 校验间隔为正 */
    private static Duration requireInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("集群定时任务 interval 必须大于 0");
        }
        return interval;
    }

    /** 解析应用名：键名的隔离维度（与各组件 store-prefix 推导同一口径），缺失即启动失败 */
    private static String requireApplicationName(Environment environment) {
        String applicationName = environment.getProperty("spring.application.name");
        if (StringUtils.isBlank(applicationName)) {
            throw new IllegalStateException("集群定时任务需要 spring.application.name（键名按应用隔离），"
                    + "未配置且无法推导，请配置 spring.application.name");
        }
        return applicationName.trim();
    }

}
