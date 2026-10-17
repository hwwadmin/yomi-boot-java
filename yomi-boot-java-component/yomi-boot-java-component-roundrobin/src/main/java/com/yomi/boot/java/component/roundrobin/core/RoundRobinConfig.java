package com.yomi.boot.java.component.roundrobin.core;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 单任务轮询策略，由配置解析后不可变
 *
 * @author zwei
 */
@Slf4j
@Getter
@Builder
public class RoundRobinConfig {

    /** 任务最小调度间隔下限 */
    private static final Duration MIN_INTERVAL = Duration.ofMillis(50);

    /** 任务名，集群互斥维度 */
    private final String name;

    /** 任务作用简介，仅用于启动日志（便于运维一眼识别任务用途），可为空 */
    private final String description;
    /**
     * <b>最小轮询间隔</b>（不是固定周期），兼作频率锁 TTL。
     * <p>成功执行后的真实周期 ≈ 任务耗时 + [frequency+randomDelayMin, frequency+randomDelayMax]
     * （默认 = 任务耗时 + [f+100ms, 2f+100ms)）：估算延迟/吞吐按频率下限算，frequency 不是周期</p>
     */
    private final Duration frequency;

    /** 成功后 jitter 下限 */
    private final Duration randomDelayMin;

    /** 成功后 jitter 上限（与 frequency 组合使用） */
    private final Duration randomDelayMax;

    /** 未获得锁时的休眠间隔 */
    private final Duration wakeUpInterval;
    /**
     * 执行锁租约。实际语义取决于 {@link #execWatchdog}：
     * <ul>
     *     <li>watchdog=true（默认）：<b>不参与锁租约</b>（租约由 Redisson 看门狗续期、无上限），仅作为 maxHold 的下限参照；</li>
     *     <li>watchdog=false：硬租约——任务要在该时长内完成，超时则锁过期、其它实例可能并发执行同一任务</li>
     * </ul>
     */
    private final Duration execLockTtl;

    /** true 时 Redisson 看门狗续期（租约无上限） */
    private final boolean execWatchdog;
    /**
     * 单次持锁时长上限，null 表示不限制。
     * <p><b>协作式</b>：到期只置取消标志并打 WARN——不强制中断任务、不停止锁续期；
     * handler 必须轮询 {@code ctx.isCancelled()} 才有效。watchdog=false 时必须 ≤ execLockTtl</p>
     */
    private final Duration maxHoldDuration;

    /** 获取执行锁的等待时间 */
    private final Duration lockWait;

    /** 停机时等待本任务线程退出 */
    private final Duration shutdownAwait;

    /** 校验任务配置合法性 */
    public void validate() {
        requireName(name);
        requireMinInterval(frequency, "frequency");
        requireMinInterval(randomDelayMin, "randomDelayMin");
        if (!randomDelayMax.isPositive() || randomDelayMax.compareTo(randomDelayMin) <= 0) {
            throw fail("randomDelayMax 必须大于 randomDelayMin");
        }
        requireMinInterval(wakeUpInterval, "wakeUpInterval");
        requireMinInterval(execLockTtl, "execLockTtl");
        if (lockWait.isNegative()) {
            throw fail("lockWait 不能为负");
        }
        if (maxHoldDuration != null) {
            requireMinInterval(maxHoldDuration, "maxHoldDuration");
            if (execWatchdog && maxHoldDuration.compareTo(execLockTtl) < 0) {
                throw fail("maxHoldDuration 不能小于 execLockTtl");
            }
            if (!execWatchdog && maxHoldDuration.compareTo(execLockTtl) > 0) {
                throw fail("watchdog 关闭时 maxHoldDuration 不能大于 execLockTtl："
                        + "锁会在 maxHold 生效前过期，其它实例可能并发执行同一任务"
                        + "（请调小 maxHoldDuration、调大 execLockTtl 或开启 execWatchdog）");
            }
        }
        if (execWatchdog && !RoundRobinDefaults.DEFAULT_EXEC_LOCK_TTL.equals(execLockTtl)) {
            log.warn("RoundRobin[{}] 注意：execWatchdog=true 时 execLockTtl={} 不参与锁租约"
                            + "（租约由 Redisson 看门狗续期、无上限），仅作为 maxHoldDuration 的下限参照；"
                            + "如需按该值硬性过期锁，请设置 execWatchdog=false",
                    name, execLockTtl);
        }
        requirePositive(shutdownAwait, "shutdownAwait");
    }

    /**
     * 生效策略单行摘要，供启动日志输出
     */
    public String toLogSummary() {
        Duration jitterLow = frequency.plus(randomDelayMin);
        Duration jitterHigh = frequency.plus(randomDelayMax);
        String maxHold = maxHoldDuration == null ? "未限制" : maxHoldDuration.toString();
        return "description=" + descriptionOrDefault()
                + ", frequency=" + frequency + "(最小间隔)"
                + ", wakeUpInterval=" + wakeUpInterval
                + ", jitterAfterSuccess=[" + jitterLow + ", " + jitterHigh + ")"
                + ", execLockTtl=" + execLockTtl
                + ", execWatchdog=" + execWatchdog
                + ", lockWait=" + lockWait
                + ", maxHoldDuration=" + maxHold
                + ", shutdownAwait=" + shutdownAwait;
    }

    /** 启动日志用的任务简介，未填写时打印占位符"未填写" */
    public String descriptionOrDefault() {
        return description == null || description.isBlank() ? "未填写" : description.trim();
    }

    /**
     * 与参考 Simple 工厂一致的默认派生
     */
    public static RoundRobinConfig simple(String name, Duration frequency, RoundRobinDefaults defaults) {
        return simple(name, null, frequency, defaults);
    }

    /**
     * 与参考 Simple 工厂一致的默认派生，并指定任务作用简介（启动日志打印）
     */
    public static RoundRobinConfig simple(String name, String description, Duration frequency,
                                          RoundRobinDefaults defaults) {
        Duration wakeUp = frequency.dividedBy(2);
        if (wakeUp.compareTo(Duration.ofMillis(100)) < 0) {
            wakeUp = Duration.ofMillis(100);
        }
        Duration randomMin = defaults.getRandomDelayMin() != null
                ? defaults.getRandomDelayMin()
                : Duration.ofMillis(100);
        Duration randomMax = defaults.getRandomDelayMax() != null
                ? defaults.getRandomDelayMax()
                : Duration.ofMillis(100).plus(frequency);
        return RoundRobinConfig.builder()
                .name(name)
                .description(description)
                .frequency(frequency)
                .randomDelayMin(randomMin)
                .randomDelayMax(randomMax)
                .wakeUpInterval(defaults.getWakeUpInterval() != null ? defaults.getWakeUpInterval() : wakeUp)
                .execLockTtl(defaults.getExecLockTtl())
                .execWatchdog(defaults.isExecWatchdog())
                .maxHoldDuration(defaults.getMaxHoldDuration())
                .lockWait(defaults.getLockWait())
                .shutdownAwait(defaults.getShutdownAwait())
                .build();
    }

    /** 校验任务名称非空 */
    private static void requireName(String taskName) {
        if (taskName == null || taskName.isBlank() || !taskName.equals(taskName.trim())) {
            throw new IllegalStateException("yomi.roundrobin 任务 name 不能为空或含首尾空格");
        }
    }

    /**
     * 构造校验失败异常。任务级参数不在 yml 里（{@code RoundRobinProperties} 只有 enabled 与 store-prefix），
     * 所以报错用 {@code @RoundRobinTask} 的同名属性名并带上任务名，便于定位对应的任务参数
     */
    private IllegalStateException fail(String detail) {
        return new IllegalStateException(detail + "（任务: " + name + "；对应 @RoundRobinTask 属性或注册方传入的间隔）");
    }

    /** 校验间隔不低于 50ms */
    private void requireMinInterval(Duration value, String field) {
        if (value == null || value.isNegative() || value.compareTo(MIN_INTERVAL) < 0) {
            throw fail(field + " 必须大于等于 50ms");
        }
    }

    /** 校验 Duration 须大于 0 */
    private void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw fail(field + " 必须大于 0");
        }
    }

}
