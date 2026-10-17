package com.yomi.boot.java.component.roundrobin;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.Objects;

/**
 * 轮询任务定义（不可变）：任务名、简介、节奏与锁策略、单轮执行体
 * <p>
 * 由 {@link RoundRobin#task} 创建；参数派生与校验在构造期完成，非法配置创建即失败。
 * <p>类有意不声明 final：测试通过覆写 {@link #toLogSummary()} 注入"循环自身代码出错"场景
 *
 * @author zwei
 */
@Getter
public class RoundRobinTask {

    /** 任务最小调度间隔下限 */
    private static final Duration MIN_INTERVAL = Duration.ofMillis(50);

    /** 成功后随机延迟下限，兼作抢锁失败休眠下限（不提供配置：与频率锁 TTL 配套的安全余量） */
    private static final Duration DEFAULT_JITTER_MIN = Duration.ofMillis(100);

    /** 停机 join 默认等待 */
    private static final Duration DEFAULT_SHUTDOWN_AWAIT = Duration.ofSeconds(30);

    /** 任务名，集群互斥维度；锁键的一部分，改名等于换锁 */
    private final String name;

    /** 任务作用简介，仅用于启动日志，可为空 */
    private final String description;

    /**
     * <b>最小轮询间隔</b>（不是固定周期），兼作频率锁 TTL。
     * <p>成功执行后的真实周期 ≈ 任务耗时 + [{@code interval}+{@link #jitterMin}, {@code interval}+{@link #jitterMax})</p>
     */
    private final Duration interval;

    /** 成功后 jitter 下限（派生固定值） */
    private final Duration jitterMin;

    /** 成功后 jitter 上限（派生：jitterMin + interval） */
    private final Duration jitterMax;

    /** 未获得锁时的休眠间隔（派生：max(interval/2, 100ms)） */
    private final Duration wakeUpInterval;

    /**
     * 执行锁租约：null = Redisson 看门狗续期（默认，租约无上限）；
     * 设值 = 硬租约，任务要在其内完成，超时锁过期、其它实例可能并发执行同一任务
     */
    private final Duration execLockTtl;

    /**
     * 单次持锁时长上限，null 表示不限制。
     * <p><b>协作式</b>：到期只置取消标志并打 WARN——不强制中断任务、不停止锁续期；
     * handler 必须轮询 {@code ctx.isCancelled()} 才有效
     */
    private final Duration maxHoldDuration;

    /** 获取执行锁的等待时间 */
    private final Duration lockWait;

    /** 停机时等待本任务线程退出 */
    private final Duration shutdownAwait;

    /** 单轮执行体 */
    private final RoundRobinHandler handler;

    @Builder
    protected RoundRobinTask(String name, String description, Duration interval, Duration execLockTtl,
                            Duration maxHoldDuration, Duration lockWait, Duration shutdownAwait,
                            RoundRobinHandler handler) {
        this.name = requireName(name);
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.interval = requireMinInterval(interval, "interval");
        this.jitterMin = DEFAULT_JITTER_MIN;
        this.jitterMax = DEFAULT_JITTER_MIN.plus(this.interval);
        Duration wakeUp = this.interval.dividedBy(2);
        this.wakeUpInterval = wakeUp.compareTo(DEFAULT_JITTER_MIN) < 0 ? DEFAULT_JITTER_MIN : wakeUp;
        this.execLockTtl = execLockTtl == null ? null : requireMinInterval(execLockTtl, "execLockTtl");
        this.maxHoldDuration = maxHoldDuration == null ? null : requireMinInterval(maxHoldDuration, "maxHoldDuration");
        if (this.maxHoldDuration != null && this.execLockTtl != null
                && this.maxHoldDuration.compareTo(this.execLockTtl) > 0) {
            throw fail("maxHoldDuration 不能大于 execLockTtl（硬租约会在 maxHold 生效前过期，"
                    + "其它实例可能并发执行同一任务；请调小 maxHoldDuration、调大 execLockTtl，"
                    + "或去掉 execLockTtl 改用看门狗续期）");
        }
        this.lockWait = lockWait == null ? Duration.ZERO : lockWait;
        if (this.lockWait.isNegative()) {
            throw fail("lockWait 不能为负");
        }
        this.shutdownAwait = shutdownAwait == null ? DEFAULT_SHUTDOWN_AWAIT : shutdownAwait;
        if (this.shutdownAwait.isZero() || this.shutdownAwait.isNegative()) {
            throw fail("shutdownAwait 必须大于 0");
        }
        this.handler = Objects.requireNonNull(handler,
                "RoundRobin 任务 handler 不能为空（任务: " + this.name + "）");
    }

    /** 生效策略单行摘要，供启动日志输出 */
    public String toLogSummary() {
        return "description=" + descriptionOrDefault()
                + ", interval=" + interval + "(最小间隔)"
                + ", wakeUpInterval=" + wakeUpInterval
                + ", jitterAfterSuccess=[" + interval.plus(jitterMin) + ", " + interval.plus(jitterMax) + ")"
                + ", execLockTtl=" + (execLockTtl == null ? "看门狗续期" : execLockTtl + "(硬租约)")
                + ", lockWait=" + lockWait
                + ", maxHoldDuration=" + (maxHoldDuration == null ? "未限制" : maxHoldDuration)
                + ", shutdownAwait=" + shutdownAwait;
    }

    /** 启动日志用的任务简介，未填写时打印占位符"未填写" */
    public String descriptionOrDefault() {
        return description == null ? "未填写" : description;
    }

    /** 校验任务名称非空 */
    private static String requireName(String taskName) {
        if (taskName == null || taskName.isBlank() || !taskName.equals(taskName.trim())) {
            throw new IllegalStateException("yomi.roundrobin 任务 name 不能为空或含首尾空格");
        }
        return taskName;
    }

    /** 校验间隔不低于 50ms，返回原值便于赋值 */
    private Duration requireMinInterval(Duration value, String field) {
        if (value == null || value.isNegative() || value.compareTo(MIN_INTERVAL) < 0) {
            throw fail(field + " 必须大于等于 50ms");
        }
        return value;
    }

    /** 构造校验失败异常，带上任务名便于定位 */
    private IllegalStateException fail(String detail) {
        return new IllegalStateException(detail + "（任务: " + name + "；对应 RoundRobin.task(...) 的参数）");
    }

}
