package com.yomi.boot.java.component.httplog.support;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;

/**
 * 服务名与介质键名解析（serviceName 隔离：多服务共库/共介质时不串流）
 *
 * @author zwei
 */
public class HttpLogKeys {

    /** 日志行归属服务名 */
    private final String serviceName;

    public HttpLogKeys(HttpLogProperties properties, Environment environment) {
        this.serviceName = resolveServiceName(properties, environment);
    }

    /** 返回配置的服务名 */
    public String getServiceName() {
        return serviceName;
    }

    /** Redis 队列键：{prefix}:{serviceName} */
    public String redisQueueKey(HttpLogProperties properties) {
        return properties.getRedis().getKeyPrefix() + ":" + serviceName;
    }

    /** Redis 消费组名（structure=stream）：默认 serviceName */
    public String redisGroup(HttpLogProperties properties) {
        String group = properties.getRedis().getGroup();
        return StringUtils.isNotBlank(group) ? group.trim() : serviceName;
    }

    /** Kafka topic：默认 yomi.httplog.{serviceName} */
    public String kafkaTopic(HttpLogProperties properties) {
        String topic = properties.getKafka().getTopic();
        return StringUtils.isNotBlank(topic) ? topic.trim() : "yomi.httplog." + serviceName;
    }

    /** Kafka 消费组：默认 {serviceName}-httplog */
    public String kafkaConsumerGroup(HttpLogProperties properties) {
        String group = properties.getKafka().getConsumerGroup();
        return StringUtils.isNotBlank(group) ? group.trim() : serviceName + "-httplog";
    }

    /** 保留期清理任务名（RoundRobin 注册名；服务维度任务，带服务名后缀） */
    public String retentionTaskName() {
        return "httpLogRetentionCleaner_" + serviceName;
    }

    /** 未配置时从 spring.application.name 推导；都缺则启动失败 */
    private static String resolveServiceName(HttpLogProperties properties, Environment environment) {
        if (StringUtils.isNotBlank(properties.getServiceName())) {
            return properties.getServiceName().trim();
        }
        String app = environment.getProperty("spring.application.name");
        if (StringUtils.isBlank(app)) {
            throw new IllegalStateException(
                    "yomi.httplog.service-name 未配置且无法从 spring.application.name 推导");
        }
        return app.trim();
    }

}
