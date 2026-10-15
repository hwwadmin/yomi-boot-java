package com.yomi.boot.java.component.httplog.store;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认落库实现：经 {@link IHttpLogRepository} 批量插入（JDBC batch，跨库安全）
 * <p>失败分类：完整性冲突（SQLState 23 / DuplicateKeyException）=重复消费，逐条隔离按"已写入"跳过；
 * 其他失败按配置重试，重试耗尽抛异常（消费循环丢弃并计数）。
 *
 * @author zwei
 */
@Slf4j
public class DefaultHttpLogStore implements HttpLogStore {

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 数据访问 */
    private final IHttpLogRepository repository;

    /** 写入失败/丢弃等指标 */
    private final HttpLogMetrics metrics;

    public DefaultHttpLogStore(HttpLogProperties properties, IHttpLogRepository repository,
                               HttpLogMetrics metrics) {
        this.properties = properties;
        this.repository = repository;
        this.metrics = metrics;
    }

    /** 批量转实体并入库 */
    @Override
    public void writeBatch(List<HttpLogRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<HttpLogEntity> entities = new ArrayList<>(batch.size());
        for (HttpLogRecord record : batch) {
            entities.add(toEntity(record));
        }
        String datasource = properties.getStore().getDatasource();
        boolean pushed = StringUtils.isNotBlank(datasource);
        if (pushed) {
            // 组件自建线程内 @DS 注解不生效，必须编程式切换
            DynamicDataSourceContextHolder.push(datasource);
        }
        try {
            writeWithRetry(entities);
            metrics.written(entities.size());
        } finally {
            if (pushed) {
                DynamicDataSourceContextHolder.poll();
            }
        }
    }

    /** 主路径批量写；完整性冲突走逐条隔离；其他异常按配置重试 */
    private void writeWithRetry(List<HttpLogEntity> entities) {
        HttpLogProperties.Store storeConfig = properties.getStore();
        int retries = Math.max(0, storeConfig.getRetryTimes());
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                repository.insertBatch(entities);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (isIntegrityViolation(e)) {
                    insertIndividually(entities);
                    return;
                }
                if (attempt < retries) {
                    log.warn("[httplog]批量写入失败，第 {}/{} 次重试: {}", attempt + 1, retries, e.getMessage());
                    sleep(storeConfig.getRetryBackoff().toMillis() * (attempt + 1));
                }
            }
        }
        throw last;
    }

    /** 逐条隔离：主键冲突按"已写入"跳过（重复消费收敛），其他单条失败计数不中断 */
    private void insertIndividually(List<HttpLogEntity> entities) {
        int conflicts = 0;
        int failed = 0;
        for (HttpLogEntity entity : entities) {
            try {
                repository.insert(entity);
            } catch (DuplicateKeyException e) {
                conflicts++;
            } catch (Exception e) {
                failed++;
                if (failed <= 3) {
                    log.warn("[httplog]逐条隔离写入失败 id={}: {}", entity.getId(), e.getMessage());
                }
            }
        }
        log.info("[httplog]批量遇完整性冲突，逐条隔离完成: 冲突(已写入)={}, 失败={}", conflicts, failed);
        if (failed > 0) {
            metrics.failed("store-individual", null);
        }
    }

    /** 是否完整性冲突（PK/唯一键）：Spring 异常或 SQLState 23xxx（四库通用的标准分类） */
    private static boolean isIntegrityViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 采集记录转持久化实体 */
    private static HttpLogEntity toEntity(HttpLogRecord record) {
        HttpLogEntity entity = new HttpLogEntity();
        // 注意：BaseEntity 的 setId 返回 BaseEntity，链式调用会断链，逐项设置
        entity.setId(record.getId());
        entity.setServiceName(record.getServiceName());
        entity.setTraceId(record.getTraceId());
        entity.setRequestTime(record.getRequestTime());
        entity.setDurationMs(record.getDurationMs());
        entity.setMethod(record.getMethod());
        entity.setUri(record.getUri());
        entity.setQueryString(record.getQueryString());
        entity.setStatus(record.getStatus());
        entity.setClientIp(record.getClientIp());
        entity.setUserId(record.getUserId());
        entity.setRequestHeaders(record.getRequestHeaders());
        entity.setRequestBody(record.getRequestBody());
        entity.setRequestBodyTruncated(record.isRequestBodyTruncated());
        entity.setResponseHeaders(record.getResponseHeaders());
        entity.setResponseBody(record.getResponseBody());
        entity.setResponseBodyTruncated(record.isResponseBodyTruncated());
        entity.setErrorMsg(record.getErrorMsg());
        entity.setAsyncStarted(record.isAsyncStarted());
        entity.setCreateTime(record.getRequestTime());
        entity.setUpdateTime(record.getRequestTime());
        entity.setCreateBy(record.getUserId());
        entity.setUpdateBy(record.getUserId());
        return entity;
    }

    /** 可中断休眠 */
    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
