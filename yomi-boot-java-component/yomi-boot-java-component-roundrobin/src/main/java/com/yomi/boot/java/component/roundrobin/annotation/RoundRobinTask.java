package com.yomi.boot.java.component.roundrobin.annotation;

import java.lang.annotation.*;

/**
 * 声明一个分布式轮询任务方法
 * <p>
 * 方法签名：{@code void xxx(RoundRobinContext ctx)} 或 {@code void xxx()}
 *
 * @author zwei
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RoundRobinTask {

    /** 任务名，集群互斥维度 */
    String name();

    /** 任务作用简介，启动日志打印（便于运维一眼识别用途）；留空打印"未填写" */
    String description() default "";

    /** 轮询频率，如 5s、500ms */
    String frequency();

    /** 空串用内置默认 100ms；成功执行后 jitter 下限 */
    String randomDelayMin() default "";

    /**
     * 空串则按 frequency 推导 jitter 上限。
     * <p>注意频率语义：{@code frequency} 是<b>最小间隔</b>，真实周期 ≈ 任务耗时 + [frequency+randomDelayMin, frequency+randomDelayMax]</p>
     */
    String randomDelayMax() default "";

    /** 空串则按 frequency 推导；未获得锁时的休眠时长 */
    String wakeUpInterval() default "";

    /**
     * 执行锁租约，空串默认 30s。实际语义取决于 {@code execWatchdog}：
     * watchdog=true 时不参与锁租约（租约由 Redisson 看门狗续期、无上限），仅作 maxHoldDuration 的下限参照，显式配置会在启动日志 WARN；
     * watchdog=false 时是硬租约，任务要在其内完成，超时则锁过期、其它实例可能并发执行同一任务
     */
    String execLockTtl() default "";

    /** 空串默认 true；true=Redisson 看门狗续期（租约无上限），false=按 execLockTtl 硬租约。只支持 true/false */
    String execWatchdog() default "";

    /**
     * 空串表示不限制；到期置 {@code RoundRobinContext} 的取消标志（<b>协作式</b>：不强制中断、不停止锁续期，
     * handler 必须轮询 {@code ctx.isCancelled()} 才有效）。
     * watchdog=false 时必须不大于 execLockTtl（超过时锁先过期、max-hold 失效，启动期直接报错）
     */
    String maxHoldDuration() default "";

    /** 空串默认 0；执行锁 tryLock 等待 */
    String lockWait() default "";

    /** 空串默认 30s；停机时等待本任务线程退出 */
    String shutdownAwait() default "";

}
