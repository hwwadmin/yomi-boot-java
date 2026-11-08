package com.yomi.boot.java.component.httplog.retention;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * 保留期清理：按 {@code create_time} 限额物理删除，直到删空或达到单轮上限
 * <p>谓词本身即"全部过期行"，因此不需要排序、也不需要游标：单条 DELETE 删 {@code chunk-size} 行，
 * 循环受 {@code max-delete-per-run} 约束（单轮占用数据库的时间有上限）。
 * <p>落后度量：每轮取最旧一行时间算 {@code lag = now - 最旧一行}，写入日志与指标
 * （{@link #getLagSeconds()}）；清理追得上时 lag ≈ 0，持续大于 {@code days} 说明单轮容量不足。
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class HttpLogRetentionCleaner {

    /** 集群定时任务名（兼作锁键） */
    public static final String TASK_NAME = "httpLogRetentionCleaner";

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 服务名 */
    private final HttpLogKeys keys;

    /** 数据访问 */
    private final IHttpLogRepository repository;

    /** 删除行数与落后度量 */
    private final HttpLogMetrics metrics;

    /** 最近一轮的落后时长（秒）：供指标读取，指标拉取不打库 */
    private volatile long lagSeconds;

    /** 任务体：分批删除直到删空或达到单轮上限 */
    public void clean() {
        HttpLogProperties.Retention retention = properties.getRetention();
        String serviceName = keys.getServiceName();
        Instant cutoff = Instant.now().minus(Duration.ofDays(retention.getDays()));
        long startedAt = System.nanoTime();
        long lagBeforeSeconds = lagSeconds(serviceName);

        long deleted = 0;
        int chunks = 0;
        while (deleted < retention.getMaxDeletePerRun()) {
            int budget = (int) Math.min(retention.getChunkSize(), retention.getMaxDeletePerRun() - deleted);
            int rows = repository.deleteExpiredLimited(serviceName, cutoff, budget);
            if (rows == 0) {
                break;
            }
            deleted += rows;
            chunks++;
        }

        this.lagSeconds = lagSeconds(serviceName);
        if (deleted > 0) {
            metrics.retentionDeleted((int) Math.min(Integer.MAX_VALUE, deleted));
            log.info("[httplog]保留期清理 t_sys_http_log deleted={}, chunks={}, elapsedMs={}, days={}, "
                            + "lagBefore={}s, lagAfter={}s", deleted, chunks,
                    (System.nanoTime() - startedAt) / 1_000_000, retention.getDays(),
                    lagBeforeSeconds, this.lagSeconds);
        }
        if (deleted >= retention.getMaxDeletePerRun()) {
            log.warn("[httplog]保留期清理达到单轮上限 max-delete-per-run={}，剩余行下轮继续"
                            + "（持续触顶说明单轮容量不足：调大上限或调小 scan-interval）",
                    retention.getMaxDeletePerRun());
        }
    }

    /** 最近一轮的落后时长（秒）：与最旧一行的时间差，表空为 0 */
    public long getLagSeconds() {
        return lagSeconds;
    }

    /** 落后时长：最旧一行时间经索引取到，单次 O(1)，与过期行数无关 */
    private long lagSeconds(String serviceName) {
        Instant oldest = repository.findOldestCreateTime(serviceName);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
    }

}
