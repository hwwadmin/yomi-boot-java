package com.yomi.boot.java.component.idempotency.stale;

import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.roundrobin.RoundRobinContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * 定时物理删除：超时「处理中」记录、已过 expire_time 的记录
 * <p>
 * 仅当 yomi.idempotency.stale-cleaner-enabled=true 且 yomi.roundrobin.enabled=true 时注册为 Bean
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyStaleCleaner {

    /** 与 RoundRobin 注册名一致，供自动配置注册任务；全局任务（整表清理），不带服务名后缀 */
    public static final String TASK_NAME = "idempotencyRecordCleaner";

    /** 幂等记录仓储 */
    private final IIdempotencyRecordRepository repository;

    /** 批次大小、超时等 */
    private final IdempotencyProperties properties;

    /** 按批次删除超时处理中与已过期的幂等记录 */
    public void clean(RoundRobinContext ctx) {
        int batch = properties.getBatchSize();
        Instant staleBefore = Instant.now().minus(properties.getStaleTimeout());
        long staleCount = repository.deleteStaleProcessing(staleBefore, batch);
        long expiredCount = repository.deleteExpired(Instant.now(), batch);
        if (staleCount > 0 || expiredCount > 0) {
            log.warn("[幂等]清理记录 staleProcessing={} expired={} staleTimeout={}",
                    staleCount, expiredCount, properties.getStaleTimeout());
        }
    }

}
