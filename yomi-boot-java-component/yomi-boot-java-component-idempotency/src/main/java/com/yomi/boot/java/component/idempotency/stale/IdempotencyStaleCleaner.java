package com.yomi.boot.java.component.idempotency.stale;

import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntUnaryOperator;

/**
 * 定时物理删除：超时「处理中」记录、已过 expire_time 的记录
 * <p>两类各自"删到删空或达到单轮上限"（限额删除 + 块大小 + 单轮上限，与 httplog/event 同口径），
 * 单轮占用数据库的时间有上限；落后度量取「已过期」一类——它才是稳态增长源，僵死一类常态为空。
 * <p>仅当 yomi.idempotency.stale-cleaner-enabled=true 时装配，由集群定时任务调度
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyStaleCleaner {

    /** 集群定时任务名（兼作锁键） */
    public static final String TASK_NAME = "idempotencyRecordCleaner";

    /** 幂等记录仓储 */
    private final IIdempotencyRecordRepository repository;

    /** 块大小、单轮上限、超时等 */
    private final IdempotencyProperties properties;

    /** 最近一轮的落后秒数（表空或已追上为 0）：最旧一行过期多久，供指标/排查读取 */
    private volatile long lagSeconds;

    /** 分类清理：僵死处理中记录、已过期记录，两类各自循环到删空或触顶 */
    public void clean() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.getStaleTimeout());
        long startedAt = System.nanoTime();

        long staleDeleted = deleteInBatches("僵死处理中记录",
                budget -> (int) repository.deleteStaleProcessing(staleBefore, budget));
        long expiredDeleted = deleteInBatches("已过期记录",
                budget -> (int) repository.deleteExpired(now, budget));

        this.lagSeconds = lagSeconds(now);
        if (staleDeleted > 0 || expiredDeleted > 0) {
            log.info("[幂等]记录清理 staleProcessing={} expired={}, lag={}s, elapsedMs={}, staleTimeout={}",
                    staleDeleted, expiredDeleted, lagSeconds, (System.nanoTime() - startedAt) / 1_000_000,
                    properties.getStaleTimeout());
        }
    }

    /** 最近一轮的落后秒数：0 表示追上；持续变大说明单轮容量不足（保留期已失真） */
    public long getLagSeconds() {
        return lagSeconds;
    }

    /** 分批删除直到删空或达到单轮上限：末块按剩余额度收窄，单轮行数精确封顶 */
    private long deleteInBatches(String category, IntUnaryOperator deleteBlock) {
        int maxPerRun = properties.getMaxDeletePerRun();
        long deleted = 0;
        while (deleted < maxPerRun) {
            int budget = (int) Math.min(properties.getChunkSize(), maxPerRun - deleted);
            int rows = deleteBlock.applyAsInt(budget);
            if (rows == 0) {
                return deleted;
            }
            deleted += rows;
        }
        log.warn("[幂等]{}清理达到单轮上限 max-delete-per-run={}，剩余行下轮继续"
                        + "（持续触顶说明单轮容量不足：调大上限或调小 scan-interval）",
                category, maxPerRun);
        return deleted;
    }

    /** 落后 = 最旧一行的 expire_time 已过期多久（表空或未过期按 0 计） */
    private long lagSeconds(Instant now) {
        Instant oldest = repository.findOldestExpireTime();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds());
    }

}
