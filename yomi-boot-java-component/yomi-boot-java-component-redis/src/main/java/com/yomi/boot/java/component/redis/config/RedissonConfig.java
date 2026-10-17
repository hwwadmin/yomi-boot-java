package com.yomi.boot.java.component.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置
 * <p>
 * 目标拓扑来自 Boot 归一化后的 {@link DataRedisConnectionDetails}：
 * 单机（host/port 或 {@code spring.data.redis.url}，含 database/username/password）、
 * 哨兵（master/nodes/sentinel-password）、集群（nodes）都支持；配置了 SslBundle 时使用 {@code rediss://}。
 * 宿主可自带 {@link RedissonClient} Bean 覆盖本配置
 *
 * @author zwei
 */
@Slf4j
@Configuration
@Conditional(RedisConfiguredCondition.class)
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown") // 确保应用关闭时关闭 Redisson 客户端
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(DataRedisConnectionDetails connectionDetails) {
        Config config = toRedissonConfig(connectionDetails);
        log.info("[redis] Redisson 客户端: mode={}, addresses={}, database={}",
                describeMode(connectionDetails), describeAddresses(connectionDetails),
                describeDatabase(connectionDetails));
        return Redisson.create(config);
    }

    /**
     * 将 Boot 的连接信息映射为 Redisson 配置（包级可见，供离线单测断言，不建连）。
     * <p>优先级：哨兵 → 集群 → 单机；只配主从（masterreplica）时抛出异常，由宿主自带 RedissonClient 覆盖</p>
     */
    static Config toRedissonConfig(DataRedisConnectionDetails details) {
        if (details == null) {
            throw new IllegalStateException("[redis] 无法获取 Redis 连接信息（DataRedisConnectionDetails）："
                    + "请配置 spring.data.redis.*（host/url/sentinel/cluster），或自定义 RedissonClient Bean 覆盖");
        }
        String scheme = details.getSslBundle() != null ? "rediss://" : "redis://";
        String username = details.getUsername();
        String password = details.getPassword();

        DataRedisConnectionDetails.Sentinel sentinel = details.getSentinel();
        if (sentinel != null) {
            Config config = new Config();
            SentinelServersConfig sentinelConfig = config.useSentinelServers()
                    .setMasterName(sentinel.getMaster())
                    .setDatabase(sentinel.getDatabase())
                    .addSentinelAddress(sentinel.getNodes().stream()
                            .map(node -> scheme + node.host() + ":" + node.port())
                            .toArray(String[]::new));
            // 哨兵节点口令与主库口令分离
            sentinelConfig.setSentinelPassword(StringUtils.defaultIfBlank(sentinel.getPassword(), password));
            applyRedisAuth(config, StringUtils.defaultIfBlank(sentinel.getUsername(), username), password);
            return config;
        }

        DataRedisConnectionDetails.Cluster cluster = details.getCluster();
        if (cluster != null) {
            if (CollectionUtils.isEmpty(cluster.getNodes())) {
                throw new IllegalStateException("[redis] spring.data.redis.cluster.nodes 未配置");
            }
            Config config = new Config();
            config.useClusterServers()
                    .addNodeAddress(cluster.getNodes().stream()
                            .map(node -> scheme + node.host() + ":" + node.port())
                            .toArray(String[]::new));
            applyRedisAuth(config, username, password);
            return config;
        }

        DataRedisConnectionDetails.Standalone standalone = details.getStandalone();
        if (standalone != null) {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(scheme + standalone.getHost() + ":" + standalone.getPort())
                    .setDatabase(standalone.getDatabase());
            applyRedisAuth(config, username, password);
            return config;
        }

        if (details.getMasterReplica() != null) {
            throw new IllegalStateException("[redis] 暂不支持主从（masterreplica）形态的 Redisson 映射："
                    + "请改用单机/哨兵/集群配置，或自定义 RedissonClient Bean 覆盖");
        }
        throw new IllegalStateException("[redis] 无法从 spring.data.redis.* 推导 Redis 拓扑："
                + "请配置 host/url/sentinel/cluster，或自定义 RedissonClient Bean 覆盖");
    }

    /** Redisson 4.7+：主库鉴权写在 {@link Config} 上，各 ServerConfig 的 setter 已弃用 */
    private static void applyRedisAuth(Config config, String username, String password) {
        if (StringUtils.isNotBlank(username)) {
            config.setUsername(username);
        }
        if (StringUtils.isNotBlank(password)) {
            config.setPassword(password);
        }
    }

    /** 描述 Redis 部署模式（日志） */
    private static String describeMode(DataRedisConnectionDetails details) {
        if (details.getSentinel() != null) {
            return "sentinel";
        }
        if (details.getCluster() != null) {
            return "cluster";
        }
        return "standalone";
    }

    /** 描述 Redis 地址（日志） */
    private static String describeAddresses(DataRedisConnectionDetails details) {
        if (details == null) {
            return "-";
        }
        DataRedisConnectionDetails.Sentinel sentinel = details.getSentinel();
        if (sentinel != null) {
            return sentinel.getNodes().toString();
        }
        DataRedisConnectionDetails.Cluster cluster = details.getCluster();
        if (cluster != null) {
            return cluster.getNodes().toString();
        }
        if (details.getStandalone() != null) {
            return details.getStandalone().getHost() + ":" + details.getStandalone().getPort();
        }
        return "-";
    }

    /** 描述 Redis 库号（日志） */
    private static int describeDatabase(DataRedisConnectionDetails details) {
        if (details == null) {
            return 0;
        }
        if (details.getSentinel() != null) {
            return details.getSentinel().getDatabase();
        }
        if (details.getStandalone() != null) {
            return details.getStandalone().getDatabase();
        }
        return 0;
    }

}
