package com.yomi.boot.java.component.redis.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * redis信息展示
 *
 * @author zwei
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Conditional(RedisConfiguredCondition.class)
public class RedisInfo {

    /** 当前 Redis 连接工厂 */
    private final RedisConnectionFactory redisConnectionFactory;

    /** Boot 解析的连接详情（日志展示） */
    private final ObjectProvider<DataRedisConnectionDetails> connectionDetailsProvider;

    @PostConstruct
    public void init() {
        DataRedisConnectionDetails details = connectionDetailsProvider.getIfAvailable();
        log.info("Redis 部署类型: {}", describeMode(details));
        log.info("Redis 连接信息 [{}]", describeTarget(details));
        log.info("Redis 连接池类型: {}", redisConnectionFactory.getClass().getName());
    }

    /** 部署类型：单机 / 哨兵 / 集群 / 主从（由 Boot 归一化后的连接信息判定） */
    static String describeMode(DataRedisConnectionDetails details) {
        if (details == null) {
            return "未知";
        }
        if (details.getSentinel() != null) {
            return "哨兵";
        }
        if (details.getCluster() != null) {
            return "集群";
        }
        if (details.getMasterReplica() != null) {
            return "主从";
        }
        if (details.getStandalone() != null) {
            return "单机";
        }
        return "未知";
    }

    /** 按 Boot 归一化后的连接信息描述实际拓扑（host/url/sentinel/cluster 都能打出来，而不是只看 host 属性） */
    private static String describeTarget(DataRedisConnectionDetails details) {
        if (details == null) {
            return "~";
        }
        String credential = StringUtils.isBlank(details.getUsername()) ? "" : details.getUsername() + "@";
        if (details.getSentinel() != null) {
            return "sentinel master=" + details.getSentinel().getMaster()
                    + ", nodes=" + details.getSentinel().getNodes()
                    + ", database=" + details.getSentinel().getDatabase() + " - " + credential;
        }
        if (details.getCluster() != null) {
            return "cluster nodes=" + details.getCluster().getNodes() + " - " + credential;
        }
        if (details.getStandalone() != null) {
            return details.getStandalone().getHost() + ":" + details.getStandalone().getPort()
                    + " - " + credential + details.getStandalone().getDatabase();
        }
        return details.getMasterReplica() != null ? "masterreplica nodes=" + details.getMasterReplica().getNodes() : "~";
    }

}
