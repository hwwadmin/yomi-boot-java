package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 单任务轮询主循环
 * <p>
 * 错误分级：<b>单轮执行</b>失败（任务异常、任务 Error、取锁/检测失败）计入连续失败并按退避节奏重试，worker 不退出；
 * <b>循环自身</b>代码（节奏、记账、生效配置摘要）出错则记 ERROR 后退出，退出日志与正常停机区分开
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RoundRobinLoop {

    /** 单次调度循环：加锁、执行、退避 */
    private final RoundRobinTick tick;

    /** 执行业务逻辑 */
    public void run(CancellationToken scheduleToken, RoundRobinConfig config, RoundRobinHandler task) {
        String name = config.getName();
        int consecutiveErrors = 0;
        boolean abnormalExit = false;
        try {
            // 生效配置摘要在 try 内：摘要取数出错同走"循环自身代码出错"，worker 退出必留 ERROR 与异常退出标记
            log.info("RoundRobin[{}] task started, 生效配置: {}", name, config.toLogSummary());
            while (!scheduleToken.isCancelled()) {
                boolean executed;
                try {
                    executed = tick.tick(scheduleToken, config, task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    // 任务与框架层的 Error/Exception 一视同仁：统一计入连续失败并退避。
                    // 恒抛 Error 的任务按退避节奏重试，连续失败计数与日志会持续增长
                    if (RoundRobinTick.shouldStopOnTickError(scheduleToken)) {
                        break;
                    }
                    consecutiveErrors++;
                    var backoff = RoundRobinBackoff.duration(consecutiveErrors);
                    if (consecutiveErrors >= RoundRobinBackoff.maxConsecutiveWarn()) {
                        log.warn("RoundRobin[{}] 连续失败次数已达 {}，当前退避 {}，请检查系统状态",
                                name, consecutiveErrors, backoff, t);
                    } else {
                        log.error("RoundRobin[{}] 任务/调度失败，consecutiveErrors={}，退避 {}",
                                name, consecutiveErrors, backoff, t);
                    }
                    if (!RoundRobinSleep.sleep(scheduleToken, backoff)) {
                        break;
                    }
                    continue;
                }
                if (executed) {
                    consecutiveErrors = 0;
                    if (!RoundRobinSleep.sleep(scheduleToken, RoundRobinJitter.duration(config))) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            // 逃出单轮容错的错误只可能来自循环自身的代码：worker 线程即将结束，这里记 ERROR 与正常停机区分开
            abnormalExit = true;
            log.error("RoundRobin[{}] 轮询循环异常退出：worker 线程已结束、本任务不再被调度"
                    + "（生命周期状态不会自动翻转，请按本条日志告警排查）", name, t);
        } finally {
            log.info("RoundRobin[{}] task stopped{}", name, abnormalExit ? "（异常退出）" : "");
        }
    }

}
