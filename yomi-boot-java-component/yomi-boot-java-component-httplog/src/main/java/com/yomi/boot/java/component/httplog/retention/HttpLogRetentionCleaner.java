package com.yomi.boot.java.component.httplog.retention;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.roundrobin.RoundRobinContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 保留期清理：按 {@code create_time} 两阶段物理删除（仓储内先分页取 id 再按 id 删），
 * 取 id 与删除分两步执行，SQL 在各数据库通用
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class HttpLogRetentionCleaner {

    /** RoundRobin 任务名（不带服务名后缀：应用隔离由 roundrobin 前缀保证） */
    public static final String TASK_NAME = "httpLogRetentionCleaner";

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 服务名与 RoundRobin 任务键 */
    private final HttpLogKeys keys;

    /** 数据访问 */
    private final IHttpLogRepository repository;

    /** RoundRobin 任务体：分批删除直到删空或达到单轮上限 */
    public void clean(RoundRobinContext ctx) {
        HttpLogProperties.Retention retention = properties.getRetention();
        Instant before = Instant.now().minus(Duration.ofDays(retention.getDays()));
        String serviceName = keys.getServiceName();
        long total = 0;
        while (total < retention.getMaxDeletePerRun() && !ctx.isCancelled()) {
            List<Long> ids = repository.selectExpiredIds(serviceName, before, retention.getBatchSize());
            if (ids.isEmpty()) {
                break;
            }
            total += repository.deleteByIds(ids);
            if (ids.size() < retention.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("[httplog]保留期清理 sys_http_log deleted={}, days={}, before={}", total,
                    retention.getDays(), before);
        }
        if (total >= retention.getMaxDeletePerRun()) {
            log.warn("[httplog]保留期清理达到单轮上限 maxDeletePerRun={}，剩余行下轮继续",
                    retention.getMaxDeletePerRun());
        }
    }

}
