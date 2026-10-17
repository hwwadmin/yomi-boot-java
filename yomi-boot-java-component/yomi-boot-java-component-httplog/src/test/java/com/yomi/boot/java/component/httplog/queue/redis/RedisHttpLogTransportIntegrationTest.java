package com.yomi.boot.java.component.httplog.queue.redis;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * list 队列上限真机验证：写 10 条、上限 5 → 队列长度 5 且保留最新 5 条
 * <p>默认跳过；配置系统属性 {@code yomi.test.redis.host}（或环境变量 {@code YOMI_REDIS_HOST}）后执行，
 * 端口/口令/库号分别读 {@code yomi.test.redis.port|password|database}（环境变量 {@code YOMI_REDIS_*}）</p>
 *
 * @author zwei
 */
class RedisHttpLogTransportIntegrationTest {

    /** 测试专用键前缀，避免污染业务队列 */
    private static final String KEY_PREFIX = "yomi:test:httplog:queue";

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate template;

    private RedisHttpLogTransport transport;

    private String queueKey;

    @BeforeEach
    void setUp() {
        String host = setting("yomi.test.redis.host", "YOMI_REDIS_HOST");
        assumeTrue(StringUtils.isNotBlank(host), "未配置 yomi.test.redis.host / YOMI_REDIS_HOST，跳过真机用例");

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host,
                intSetting("yomi.test.redis.port", "YOMI_REDIS_PORT", 6379));
        String password = setting("yomi.test.redis.password", "YOMI_REDIS_PASSWORD");
        if (StringUtils.isNotBlank(password)) {
            config.setPassword(password);
        }
        config.setDatabase(intSetting("yomi.test.redis.database", "YOMI_REDIS_DATABASE", 0));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        HttpLogProperties properties = new HttpLogProperties();
        properties.setServiceName("it");
        properties.getRedis().setKeyPrefix(KEY_PREFIX);
        properties.getRedis().setMaxLength(5);
        HttpLogKeys keys = new HttpLogKeys(properties, null);
        queueKey = keys.redisQueueKey(properties);
        transport = new RedisHttpLogTransport(properties, keys, template, desensitizer(), metrics());
        template.delete(queueKey);
    }

    @AfterEach
    void tearDown() {
        if (template != null && queueKey != null) {
            template.delete(queueKey);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void listQueue_overLimit_keepsNewest() {
        transport.writeBatch(records(10));

        assertEquals(5, template.opsForList().size(queueKey).intValue());
        assertEquals(List.of("/6", "/7", "/8", "/9", "/10"),
                uris(template.opsForList().range(queueKey, 0, -1)));
    }

    @Test
    void listQueue_multiBatch_neverExceedsLimit() {
        transport.writeBatch(records(3));
        transport.writeBatch(records(4));
        transport.writeBatch(records(5));

        assertEquals(5, template.opsForList().size(queueKey).intValue());
    }

    /** 队列内 payload → uri 列表（按队列顺序，队首=最旧） */
    private static List<String> uris(List<String> payloads) {
        List<String> uris = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            uris.add(JsonUtils.parse(payload, HttpLogRecord.class).getUri());
        }
        return uris;
    }

    private static String setting(String systemProperty, String environmentVariable) {
        String value = System.getProperty(systemProperty);
        return StringUtils.isNotBlank(value) ? value : System.getenv(environmentVariable);
    }

    private static int intSetting(String systemProperty, String environmentVariable, int defaultValue) {
        String value = setting(systemProperty, environmentVariable);
        return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value.trim());
    }

    private static HttpLogMetrics metrics() {
        return new HttpLogMetrics(new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
    }

    private static HttpLogDesensitizer desensitizer() {
        HttpLogProperties.Desensitize desensitize = new HttpLogProperties.Desensitize();
        desensitize.setKeyTypes(Map.of());
        return new HttpLogDesensitizer((type, value) -> value, desensitize);
    }

    private static List<HttpLogRecord> records(int count) {
        List<HttpLogRecord> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(HttpLogRecord.builder().id((long) i).uri("/" + i).build());
        }
        return list;
    }

}
