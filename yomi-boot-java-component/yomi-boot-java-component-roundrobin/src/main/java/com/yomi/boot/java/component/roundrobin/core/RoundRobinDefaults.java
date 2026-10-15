package com.yomi.boot.java.component.roundrobin.core;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * 全局默认锁与抖动参数
 *
 * @author zwei
 */
@Getter
@Builder
public class RoundRobinDefaults {

    /** 执行锁租约默认值（与 {@link RoundRobinConfig#validate()} 的"显式配置"判定共用） */
    public static final Duration DEFAULT_EXEC_LOCK_TTL = Duration.ofSeconds(30);

    /** jitter 随机延迟下限 */
    @Builder.Default
    private final Duration randomDelayMin = Duration.ofMillis(100);

    /** jitter 随机延迟上限，null 时由解析器按 frequency 推导 */
    private final Duration randomDelayMax;

    /** 抢锁失败休眠，null 时由解析器按 frequency 推导 */
    private final Duration wakeUpInterval;

    /** 执行锁硬租约默认时长 */
    @Builder.Default
    private final Duration execLockTtl = DEFAULT_EXEC_LOCK_TTL;

    /** 是否启用 Redisson 看门狗续期 */
    @Builder.Default
    private final boolean execWatchdog = true;

    /** null 表示不启用 max-hold */
    private final Duration maxHoldDuration;

    /** 抢执行锁最大等待 */
    @Builder.Default
    private final Duration lockWait = Duration.ZERO;

    /** 停机 join 默认等待 */
    @Builder.Default
    private final Duration shutdownAwait = Duration.ofSeconds(30);

}
