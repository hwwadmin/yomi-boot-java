package com.yomi.boot.java.component.httplog.config;

import com.yomi.boot.java.component.httplog.model.HttpLogMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置默认值与启动校验测试
 *
 * @author zwei
 */
class HttpLogPropertiesTest {

    @Test
    void defaults() {
        HttpLogProperties properties = new HttpLogProperties();
        assertFalse(properties.isEnabled());
        assertEquals(HttpLogMode.MEMORY, properties.getMode());
        assertEquals(HttpLogProperties.RedisStructure.LIST, properties.getRedis().getStructure());
        assertEquals(50_000, properties.getRedis().getMaxLength());
        assertEquals(1000, properties.getBuffer().getBatchSize());
        assertEquals(4096, properties.getBuffer().getCapacity());
        assertEquals(Duration.ofMillis(500), properties.getBuffer().getFlushInterval());
        assertEquals(7, properties.getRetention().getDays());
        assertTrue(properties.getRetention().isEnabled());
        assertEquals(1.0, properties.getCollect().getSampleRate());
        assertEquals(8192, properties.getCollect().getMaxRequestBodyBytes());
        assertEquals(8192, properties.getCollect().getMaxResponseBodyBytes());
        assertTrue(properties.getDesensitize().isEnabled());
        assertTrue(properties.getDesensitize().isBody());
        assertFalse(properties.getDesensitize().isInRequestThread());
    }

    @Test
    void validate_invalidBatchSize_failsFast() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.getBuffer().setBatchSize(0);
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("batch-size"), ex.getMessage());
    }

    @Test
    void validate_invalidSampleRate_failsFast() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.getCollect().setSampleRate(0);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_bodyLimitBeyondTextColumn_failsFast() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.getCollect().setMaxRequestBodyBytes(100_000);
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("60000"), ex.getMessage());
    }

    @Test
    void validate_invalidRetentionDays_failsFast() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.getRetention().setDays(0);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validate_disabled_skipsChecks() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.getBuffer().setBatchSize(0);
        properties.getRetention().setDays(0);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void validate_negativeRedisMaxLength_failsFast() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.setMode(HttpLogMode.REDIS);
        properties.getRedis().setMaxLength(-1);
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("max-length"), ex.getMessage());
    }

    @Test
    void validate_zeroRedisMaxLength_allowed() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.setMode(HttpLogMode.REDIS);
        properties.getRedis().setMaxLength(0);
        assertDoesNotThrow(properties::validate);
    }

}
