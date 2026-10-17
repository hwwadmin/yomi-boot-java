package com.yomi.boot.java.component.redis.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node;
import org.springframework.boot.ssl.SslBundle;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boot 连接信息 → Redisson 配置的映射（纯函数，不建连）：
 * 单机（host/url 归一化后）、哨兵、集群、SSL 都要映射正确，主从与缺配置明确报错
 */
class RedissonConfigMappingTest {

    @Test
    void mapsStandaloneWithCredentialsAndDatabase() {
        Config config = RedissonConfig.toRedissonConfig(standalone("127.0.0.1", 6380, 1, "user", "pass"));

        SingleServerConfig single = config.useSingleServer();
        assertEquals("redis://127.0.0.1:6380", single.getAddress());
        assertEquals(1, single.getDatabase());
        assertEquals("user", config.getUsername());
        assertEquals("pass", config.getPassword());
    }

    @Test
    void usesRedissSchemeWhenSslBundlePresent() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.ssl = true;
        details.standalone = node("10.0.0.9", 6380);

        SingleServerConfig single = RedissonConfig.toRedissonConfig(details).useSingleServer();

        assertEquals("rediss://10.0.0.9:6380", single.getAddress(), "配置了 SslBundle 必须使用 rediss://");
    }

    @Test
    void mapsSentinelWithMasterNodesAndSentinelPassword() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.sentinelMaster = "mymaster";
        details.sentinelNodes = List.of(new Node("127.0.0.1", 26379), new Node("127.0.0.1", 26380));
        details.sentinelPassword = "sentpass";
        details.sentinelDatabase = 3;

        SentinelServersConfig sentinel = RedissonConfig.toRedissonConfig(details).useSentinelServers();

        assertEquals("mymaster", sentinel.getMasterName());
        List<String> addresses = sentinel.getSentinelAddresses();
        assertEquals(2, addresses.size(), addresses.toString());
        assertTrue(addresses.contains("redis://127.0.0.1:26379"), addresses.toString());
        assertTrue(addresses.contains("redis://127.0.0.1:26380"), addresses.toString());
        assertEquals("sentpass", sentinel.getSentinelPassword());
        assertEquals(3, sentinel.getDatabase());
    }

    @Test
    void mapsSentinelWithDistinctMasterAndSentinelPasswords() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.sentinelMaster = "mymaster";
        details.sentinelNodes = List.of(new Node("127.0.0.1", 26379));
        details.sentinelPassword = "sentpass";
        details.password = "redispass";

        Config config = RedissonConfig.toRedissonConfig(details);
        SentinelServersConfig sentinel = config.useSentinelServers();

        assertEquals("sentpass", sentinel.getSentinelPassword());
        assertEquals("redispass", config.getPassword());
    }

    @Test
    void mapsClusterWithNodesAndCredentials() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.clusterNodes = List.of(new Node("127.0.0.1", 7000), new Node("127.0.0.1", 7001));
        details.username = "user";
        details.password = "pass";

        Config config = RedissonConfig.toRedissonConfig(details);
        ClusterServersConfig cluster = config.useClusterServers();

        List<String> addresses = cluster.getNodeAddresses();
        assertEquals(2, addresses.size(), addresses.toString());
        assertTrue(addresses.contains("redis://127.0.0.1:7000"), addresses.toString());
        assertTrue(addresses.contains("redis://127.0.0.1:7001"), addresses.toString());
        assertEquals("user", config.getUsername());
        assertEquals("pass", config.getPassword());
    }

    @Test
    void masterReplicaOnlyFailsWithClearMessage() {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.masterReplicaNodes = List.of(new Node("127.0.0.1", 6379));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RedissonConfig.toRedissonConfig(details));

        assertTrue(ex.getMessage().contains("主从"), ex.getMessage());
        assertTrue(ex.getMessage().contains("RedissonClient"), "应给出自定义覆盖的出路: " + ex.getMessage());
    }

    @Test
    void missingDetailsFailsWithClearMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RedissonConfig.toRedissonConfig(null));

        assertTrue(ex.getMessage().contains("spring.data.redis"), ex.getMessage());
        assertTrue(ex.getMessage().contains("RedissonClient"), ex.getMessage());
    }

    /** 构造单机 Redis 连接详情 */
    private static RedissonConfigTestDetails standalone(String host, int port, int database,
                                                        String username, String password) {
        RedissonConfigTestDetails details = new RedissonConfigTestDetails();
        details.standalone = node(host, port);
        details.standaloneDatabase = database;
        details.username = username;
        details.password = password;
        return details;
    }

    /** 构造 Redis 节点地址 */
    private static Node node(String host, int port) {
        return new Node(host, port);
    }

    /** 可编排的连接信息替身：默认全部为空（接口 default 方法返回 null） */
    static class RedissonConfigTestDetails implements DataRedisConnectionDetails {

        Node standalone;
        int standaloneDatabase;
        String sentinelMaster;
        List<Node> sentinelNodes;
        String sentinelPassword;
        int sentinelDatabase;
        List<Node> clusterNodes;
        List<Node> masterReplicaNodes;
        String username;
        String password;
        boolean ssl;

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public SslBundle getSslBundle() {
            if (!ssl) {
                return null;
            }
            return (SslBundle) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SslBundle.class}, (proxy, method, args) -> null);
        }

        @Override
        public Standalone getStandalone() {
            if (standalone == null) {
                return null;
            }
            Node node = standalone;
            return new Standalone() {
                @Override
                public String getHost() {
                    return node.host();
                }

                @Override
                public int getPort() {
                    return node.port();
                }

                @Override
                public int getDatabase() {
                    return standaloneDatabase;
                }
            };
        }

        @Override
        public Sentinel getSentinel() {
            if (sentinelNodes == null) {
                return null;
            }
            return new Sentinel() {
                @Override
                public int getDatabase() {
                    return sentinelDatabase;
                }

                @Override
                public String getMaster() {
                    return sentinelMaster;
                }

                @Override
                public List<Node> getNodes() {
                    return sentinelNodes;
                }

                @Override
                public String getUsername() {
                    return username;
                }

                @Override
                public String getPassword() {
                    return sentinelPassword;
                }
            };
        }

        @Override
        public Cluster getCluster() {
            if (clusterNodes == null) {
                return null;
            }
            return () -> clusterNodes;
        }

        @Override
        public MasterReplica getMasterReplica() {
            if (masterReplicaNodes == null) {
                return null;
            }
            return () -> masterReplicaNodes;
        }
    }

}
