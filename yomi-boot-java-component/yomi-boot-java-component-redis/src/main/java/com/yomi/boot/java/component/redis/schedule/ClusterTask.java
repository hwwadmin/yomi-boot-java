package com.yomi.boot.java.component.redis.schedule;

import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.RedisUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
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
 *   <li><b>轮次名额</b> {@code yomi:schedule:{应用}:turn:{任务}}：{@code SET NX}、TTL = 间隔，
 *       拿到名额的实例才执行本轮——集群每间隔只放行一台；持有方挂死也不用干预，名额到期自动放行</li>
 *   <li><b>执行锁</b> {@code yomi:schedule:{应用}:lock:{任务}}：Redisson 看门狗租期，
 *       上一轮仍在执行时下一轮跳过，长任务不会与下一轮并发</li>
 * </ul>
 * 任一步未通过本轮静默跳过（正常情况）；任务体异常记 ERROR 后继续下一轮，调度不中断。
 * 容器停机时优雅退出：等当前一轮结束（等待见 {@link #shutdownAwait}，不超过容器给的阶段停机超时）、不中断；
 * 超时后的残留线程是守护线程，不阻塞 JVM 退出。
 * <p>
 * {@code environment} 由 Spring 注入（{@code @Bean} 方法参数），键名与停机等待都在构造期算定：缺
 * {@code spring.application.name}、或阶段停机超时配成负值时 Bean 创建即失败。任务名即集群互斥维度，改名等于换键。
 * <p>
 * 停机等待按 Boot 的 {@link LifecycleProperties#getTimeoutPerShutdownPhase()}（阶段停机超时，与容器真正生效的值同源）
 * 推算；宿主若替换 {@code lifecycleProcessor} Bean 或用 {@code LifecycleProcessor#setTimeoutForShutdownPhase}
 * 给本阶段单独设了更短超时，请自行保证该值不小于本等待值
 *
 * @author zwei
 */
@Slf4j
public class ClusterTask implements SmartLifecycle {

    /** 键前缀：yomi:schedule:{应用}:turn|lock:{任务} */
    private static final String KEY_PREFIX = "yomi:schedule:";

    /** 最小间隔：更小的间隔视为配置错误（Duration 绑定里裸数字按毫秒解析，1ms 级间隔会把 Redis 打成热点） */
    private static final Duration MIN_INTERVAL = Duration.ofMillis(50);

    /** 自身停机等待上限：宿主把阶段超时配得很大时也不无限等 */
    private static final Duration SHUTDOWN_AWAIT_CAP = Duration.ofSeconds(30);

    /** 轮次名额的持有者标识（pid@host），仅用于排查谁占着本轮 */
    private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

    /** 任务名：日志标识与键名的一部分 */
    @Getter
    private final String taskName;

    /** 调度间隔：上一轮结束到下一轮开始 */
    @Getter
    private final Duration interval;

    /** 轮次名额键（构造期按 spring.application.name 解析） */
    @Getter
    private final String turnKey;

    /** 执行锁键（构造期按 spring.application.name 解析） */
    @Getter
    private final String lockKey;

    /** 停机等待当前一轮结束的上限（构造期按 spring.lifecycle.timeout-per-shutdown-phase 推算） */
    @Getter
    private final Duration shutdownAwait;

    /** 单轮执行体 */
    private final Runnable action;

    /** 单线程调度器，随容器启停 */
    private volatile ThreadPoolTaskScheduler scheduler;

    /** 是否已启动 */
    private volatile boolean running;

    /**
     * @param taskName          任务名（集群互斥维度与键名的一部分，改名等于换键）
     * @param interval          调度间隔，不得小于 {@link #MIN_INTERVAL}
     * @param environment       Spring 注入的环境（键名按 {@code spring.application.name} 隔离应用）
     * @param lifecycleProperties Spring 注入的阶段停机配置（Boot 自带；停机等待按它的取值推算）
     * @param action            单轮执行体
     */
    public ClusterTask(String taskName, Duration interval, Environment environment,
                       LifecycleProperties lifecycleProperties, Runnable action) {
        this.taskName = requireTaskName(taskName);
        this.interval = requireInterval(interval);
        this.action = Objects.requireNonNull(action, "集群定时任务执行体不能为空（任务: " + taskName + "）");
        Environment env = Objects.requireNonNull(environment, "集群定时任务 environment 不能为空（由 Spring 注入）");
        String base = KEY_PREFIX + requireApplicationName(env) + ":";
        this.turnKey = base + "turn:" + taskName;
        this.lockKey = base + "lock:" + taskName;
        this.shutdownAwait = resolveShutdownAwait(Objects.requireNonNull(lifecycleProperties,
                "集群定时任务 lifecycleProperties 不能为空（Boot 应用自带；非 Boot 宿主请自行注册）"));
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix(taskName + "-");
        // 优雅停机：等当前一轮跑完再退出、不中断；守护线程，超时后的残留线程不阻塞 JVM 退出（任务幂等，可安全放弃）
        taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        taskScheduler.setAwaitTerminationMillis(shutdownAwait.toMillis());
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        taskScheduler.scheduleWithFixedDelay(this::runOnce, interval);
        this.scheduler = taskScheduler;
        this.running = true;
        log.info("[集群定时任务]已启动: task={}, interval={}, shutdownWait={}, turnKey={}, lockKey={}",
                taskName, interval, shutdownAwait, turnKey, lockKey);
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

    /** 单轮：抢名额 → 抢执行锁 → 执行；任一步未通过本轮跳过；失败记 ERROR，下一轮继续 */
    private void runOnce() {
        try {
            if (!tryAcquireTurn(turnKey)) {
                log.debug("[集群定时任务]本轮跳过，其它实例已拿到本轮名额: task={}", taskName);
                return;
            }
            LockUtils.tryLock(lockKey, () -> {
                action.run();
                return null;
            }, "集群定时任务抢执行锁失败");
        } catch (LockBusyException e) {
            log.debug("[集群定时任务]本轮跳过，上一轮仍在执行: task={}", taskName);
        } catch (Exception t) {
            // 通常来说异常应该在action任务中就捕获并处理，这里只做兜底记录
            log.error("[集群定时任务]执行失败: task={}", taskName, t);
        }
    }

    /**
     * 抢轮次名额（{@code SET NX}，TTL = 间隔）：名额在集群内每间隔只放行一个实例；
     * 持有方挂死无需干预——名额到期自动放行，长任务由执行锁看门狗兜底
     * <p>可覆写方法：测试注入名额结果（沿用任务参数的注入缝做法），生产走 Redis
     */
    protected boolean tryAcquireTurn(String turnKey) {
        return Boolean.TRUE.equals(RedisUtils.stringRedisTemplate().opsForValue()
                .setIfAbsent(turnKey, INSTANCE_ID, interval));
    }

    /** 校验任务名非空（日志标识与键名的一部分） */
    private static String requireTaskName(String taskName) {
        if (StringUtils.isBlank(taskName) || !taskName.equals(taskName.trim())) {
            throw new IllegalArgumentException("集群定时任务 taskName 不能为空或含首尾空白");
        }
        return taskName;
    }

    /** 校验间隔不低于 {@link #MIN_INTERVAL} */
    private static Duration requireInterval(Duration interval) {
        if (interval == null || interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("集群定时任务 interval 不能小于 " + MIN_INTERVAL.toMillis()
                    + "ms（当前: " + interval + "；yml 裸数字按毫秒解析）");
        }
        return interval;
    }

    /** 解析应用名：键名的隔离维度（与各组件 store-prefix 推导同一口径），缺失即 Bean 创建失败 */
    private static String requireApplicationName(Environment environment) {
        String applicationName = environment.getProperty("spring.application.name");
        if (StringUtils.isBlank(applicationName)) {
            throw new IllegalStateException("集群定时任务需要 spring.application.name（键名按应用隔离），"
                    + "未配置且无法推导，请配置 spring.application.name");
        }
        return applicationName.trim();
    }

    /**
     * 算定停机等待：{@code min(自身上限, 阶段停机超时 × 0.8)}——不得大于容器给的阶段超时，
     * 留 20% 余量给同阶段其它 Bean 与容器自身开销。
     * <p>阶段超时取自 Boot 的 {@link LifecycleProperties}（Boot 会把它应用到 LifecycleProcessor，
     * 所以这就是真正生效的值；未显式配置时即其默认值，注意该默认值不在 Environment 里，读属性会得到 null）
     */
    private static Duration resolveShutdownAwait(LifecycleProperties lifecycleProperties) {
        Duration phaseTimeout = lifecycleProperties.getTimeoutPerShutdownPhase();
        if (phaseTimeout == null || phaseTimeout.isNegative()) {
            throw new IllegalStateException("spring.lifecycle.timeout-per-shutdown-phase 不能为负（当前: " + phaseTimeout + "）："
                    + "集群定时任务的停机等待按它推算，且 Spring 未定义负值语义，请改为非负值");
        }
        // 阶段超时 ≥ 2×上限 时 80% 必然超过上限，直接封顶（也避免超大 Duration 乘法溢出）
        if (phaseTimeout.compareTo(SHUTDOWN_AWAIT_CAP.multipliedBy(2)) >= 0) {
            return SHUTDOWN_AWAIT_CAP;
        }
        Duration bounded = phaseTimeout.multipliedBy(4).dividedBy(5);
        return bounded.compareTo(SHUTDOWN_AWAIT_CAP) < 0 ? bounded : SHUTDOWN_AWAIT_CAP;
    }

}
