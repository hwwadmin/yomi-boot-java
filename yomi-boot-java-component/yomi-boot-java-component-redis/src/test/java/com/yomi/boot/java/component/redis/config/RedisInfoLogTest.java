package com.yomi.boot.java.component.redis.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.redis.config.RedissonConfigMappingTest.RedissonConfigTestDetails;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * redis 启动信息日志：必须打出部署类型（单机/哨兵/集群/主从）与实际连接目标——
 * url/sentinel/cluster 写法下也能从启动日志一眼确认组件连到了哪种部署
 */
class RedisInfoLogTest {

    @Test
    void describeModeCoversAllDeploymentTypes() {
        assertEquals("未知", RedisInfo.describeMode(null));
        assertEquals("单机", RedisInfo.describeMode(standalone()));
        assertEquals("哨兵", RedisInfo.describeMode(sentinel()));
        assertEquals("集群", RedisInfo.describeMode(cluster()));
        assertEquals("主从", RedisInfo.describeMode(masterReplica()));
    }

    @Test
    void initLogsDeploymentTypeAndTarget() {
        RedissonConfigTestDetails details = sentinel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RedisInfo.class);
        logger.addAppender(appender);
        try {
            new RedisInfo(stubConnectionFactory(), providerOf(details)).init();
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
        assertTrue(logs.contains("Redis 部署类型: 哨兵"), logs);
        assertTrue(logs.contains("Redis 连接信息 ["), logs);
        assertTrue(logs.contains("master=mymaster"), "目标信息应包含哨兵 master: " + logs);
        assertTrue(logs.contains("Redis 连接池类型: "), logs);
    }

    /** 构造单机 Redis 连接详情 */
    private static RedissonConfigTestDetails standalone() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.standalone = new Node("127.0.0.1", 6379);
        return details;
    }

    /** 构造 Sentinel Redis 连接详情 */
    private static RedissonConfigTestDetails sentinel() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.sentinelMaster = "mymaster";
        details.sentinelNodes = List.of(new Node("127.0.0.1", 26379));
        return details;
    }

    /** 构造 Cluster Redis 连接详情 */
    private static RedissonConfigTestDetails cluster() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.clusterNodes = List.of(new Node("127.0.0.1", 7000));
        return details;
    }

    /** 构造主从 Redis 连接详情 */
    private static RedissonConfigTestDetails masterReplica() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.masterReplicaNodes = List.of(new Node("127.0.0.1", 6379));
        return details;
    }

    /** 构造 Lettuce 连接工厂桩 */
    private static RedisConnectionFactory stubConnectionFactory() {
        return (RedisConnectionFactory) Proxy.newProxyInstance(RedisInfoLogTest.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class}, (proxy, method, args) -> null);
    }

    /** 将单例包装为 ObjectProvider */
    private static ObjectProvider<DataRedisConnectionDetails> providerOf(DataRedisConnectionDetails details) {
        return new ObjectProvider<>() {
            @Override
            public DataRedisConnectionDetails getObject() {
                return details;
            }

            @Override
            public DataRedisConnectionDetails getObject(Object... args) {
                return details;
            }

            @Override
            public DataRedisConnectionDetails getIfAvailable() {
                return details;
            }

            @Override
            public DataRedisConnectionDetails getIfUnique() {
                return details;
            }

            @Override
            public java.util.stream.Stream<DataRedisConnectionDetails> stream() {
                return java.util.stream.Stream.of(details);
            }
        };
    }

}
