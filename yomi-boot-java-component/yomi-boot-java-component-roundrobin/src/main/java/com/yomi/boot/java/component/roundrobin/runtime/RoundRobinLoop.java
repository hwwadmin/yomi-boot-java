package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 单任务轮询主循环
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
        log.info("RoundRobin[{}] task started", config.getName());
        int consecutiveErrors = 0;
        try {
            while (!scheduleToken.isCancelled()) {
                try {
                    boolean executed = tick.tick(scheduleToken, config, task);
                    if (executed) {
                        consecutiveErrors = 0;
                        if (!RoundRobinSleep.sleep(scheduleToken, RoundRobinJitter.duration(config))) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (RoundRobinTick.shouldStopOnTickError(scheduleToken)) {
                        break;
                    }
                    consecutiveErrors++;
                    var backoff = RoundRobinBackoff.duration(consecutiveErrors);
                    if (consecutiveErrors >= RoundRobinBackoff.maxConsecutiveWarn()) {
                        log.warn("RoundRobin[{}] 连续失败次数已达 {}，当前退避 {}，请检查系统状态",
                                config.getName(), consecutiveErrors, backoff, e);
                    } else {
                        log.error("RoundRobin[{}] 任务/调度失败，consecutiveErrors={}，退避 {}",
                                config.getName(), consecutiveErrors, backoff, e);
                    }
                    if (!RoundRobinSleep.sleep(scheduleToken, backoff)) {
                        break;
                    }
                }
            }
        } finally {
            log.info("RoundRobin[{}] task stopped", config.getName());
        }
    }

}
