package com.yomi.boot.java.component.idempotency.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 配置前缀 {@code yomi.idempotency}
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.idempotency")
public class IdempotencyProperties {

    /** 是否启用幂等组件 */
    private boolean enabled = false;

    /**
     * Redis 键前缀；未配置时按 {@code yomi:idempotency:{spring.application.name}} 推导
     */
    private String storePrefix;

    /** 成功结果在 Redis 中的存活时间 */
    private Duration resultCacheTtl = Duration.ofMinutes(5);

    /** 写入 DB 时 expire_time = 当前时间 + 本字段时长 */
    private Duration recordRetention = Duration.ofDays(7);

    /** 抢分布式锁最长等待 */
    private Duration lockWait = Duration.ofSeconds(3);

    /** 持锁租期；0 表示 Redisson 看门狗续期 */
    private Duration lockLease = Duration.ZERO;

    /** 是否注册轮询清理任务 */
    private boolean staleCleanerEnabled = false;

    /** 清理任务间隔 */
    private Duration scanInterval = Duration.ofMinutes(10);

    /** update_time 早于该阈值的「处理中」记录将被删除 */
    private Duration staleTimeout = Duration.ofMinutes(60);

    /** 每轮清理最多删除行数 */
    private int batchSize = 100;

    /** 启用时校验必填项与取值范围 */
    public void validate() {
        if (!enabled) {
            return;
        }
        requirePositive(resultCacheTtl, "yomi.idempotency.result-cache-ttl");
        requirePositive(recordRetention, "yomi.idempotency.record-retention");
        requirePositive(lockWait, "yomi.idempotency.lock-wait");
        if (lockLease != null && lockLease.isNegative()) {
            throw new IllegalStateException("yomi.idempotency.lock-lease 不能为负数");
        }
        if (!staleCleanerEnabled) {
            return;
        }
        requireAtLeast(scanInterval, Duration.ofMinutes(1), "yomi.idempotency.scan-interval");
        requireAtLeast(staleTimeout, Duration.ofMinutes(5), "yomi.idempotency.stale-timeout");
        if (batchSize <= 0) {
            throw new IllegalStateException("yomi.idempotency.batch-size 必须大于 0");
        }
    }

    /** 校验 Duration 须大于 0 */
    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 必须大于 0");
        }
    }

    /** 校验 Duration 不低于下限 */
    private static void requireAtLeast(Duration value, Duration min, String key) {
        if (value == null || value.compareTo(min) < 0) {
            throw new IllegalStateException(key + " 不能小于 " + min.toMinutes() + " 分钟");
        }
    }

}
