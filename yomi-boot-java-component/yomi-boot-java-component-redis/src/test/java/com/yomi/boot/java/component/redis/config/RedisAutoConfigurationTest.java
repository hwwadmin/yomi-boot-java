package com.yomi.boot.java.component.redis.config;

import com.yomi.boot.java.component.redis.ops.RedisSupport;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * redis 组件装配验证（离线）：
 * <ol>
 *   <li>imports 清单声明正确；</li>
 *   <li>未配置 {@code spring.data.redis.*} 连接目标时组件不装配；
 *       即使 Boot 已创建指向 localhost 的默认连接工厂，组件仍保持不激活（Redisson 启动即建连）；</li>
 *   <li>host / url / sentinel / cluster 四种写法都能把组件装配起来。</li>
 * </ol>
 * 激活路径的连通性（真实 Redisson 建连）由 quick-starter 现网流程覆盖；本测试统一注册 stub RedissonClient，
 * 跳过 Redisson.create 真实建连
 */
class RedisAutoConfigurationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void importsFileDeclaresAutoConfiguration() throws IOException {
        String content = readImportsResource();
        assertTrue(content.contains(RedisAutoConfiguration.class.getName()),
                "imports 清单须声明 RedisAutoConfiguration");
    }

    @Test
    void allMembersStayInactiveWithoutRedisTargetConfig() {
        // 什么都没配：Boot 的连接工厂仍会被创建（默认 localhost），但组件必须保持不激活
        runner().run(context -> {
            assertTrue(context.getBeanNamesForType(RedisConnectionFactory.class).length > 0,
                    "前提：Boot 在未配置时也会创建默认连接工厂，这正是不能按 @ConditionalOnBean 激活的原因");
            assertFalse(context.containsBean("commonRedisTemplate"));
            assertFalse(context.containsBean("redisStreamListenerContainer"));
            assertFalse(context.containsBean("redisSupport"));
            assertFalse(context.containsBean("redisInfo"));
        });
    }

    @Test
    void assemblesWithHostForm() {
        runner().withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=6379")
                .run(this::assertComponentAssembled);
    }

    @Test
    void assemblesWithUrlForm() {
        // 仅配置 url、无 host 时也应装配 Redis 组件
        runner().withPropertyValues("spring.data.redis.url=redis://user:pass@127.0.0.1:6380/1")
                .run(this::assertComponentAssembled);
    }

    @Test
    void assemblesWithSentinelForm() {
        runner().withPropertyValues("spring.data.redis.sentinel.master=mymaster",
                        "spring.data.redis.sentinel.nodes=127.0.0.1:26379,127.0.0.1:26380")
                .run(this::assertComponentAssembled);
    }

    @Test
    void assemblesWithClusterForm() {
        runner().withPropertyValues("spring.data.redis.cluster.nodes=127.0.0.1:7000,127.0.0.1:7001")
                .run(this::assertComponentAssembled);
    }

    /** 断言测试条件：ComponentAssembled */
    private void assertComponentAssembled(AssertableApplicationContext context) {
        if (context.getStartupFailure() != null) {
            throw new AssertionError("组件装配不应失败", context.getStartupFailure());
        }
        assertNotNull(context.getBean(CommonRedisTemplate.class), "CommonRedisTemplate 必须装配");
        assertNotNull(context.getBean(StringRedisTemplate.class), "StringRedisTemplate 由 Boot 提供");
        assertTrue(context.getBeansOfType(StringRedisTemplate.class).size() == 1, "模板 Bean 不能重复注册");
        assertNotNull(context.getBean(RedisSupport.class));
        assertNotNull(context.getBean(RedisInfo.class));
        assertTrue(context.containsBean("redisStreamListenerContainer"));
        assertNotNull(context.getBean(RedissonClient.class), "RedissonClient 必须可用（本测试为 stub，避免真实建连）");
    }

    /**
     * 双通道：宿主扫描 com.yomi（会直接扫到 @Configuration/@Component 本身）+ imports 清单同时注册，
     * 必须去重、且不产生第二份 Bean（这是 scan 模式宿主的真实接法）
     */
    @Test
    void componentScanAndImportsRegisterExactlyOneInstanceOfEachBean() {
        runner().withUserConfiguration(ScanBootstrap.class)
                .withPropertyValues("spring.data.redis.host=127.0.0.1")
                .run(context -> {
                    if (context.getStartupFailure() != null) {
                        throw new AssertionError("双通道装配不应失败", context.getStartupFailure());
                    }
                    assertEquals(1, context.getBeansOfType(CommonRedisTemplate.class).size());
                    assertEquals(1, context.getBeansOfType(StringRedisTemplate.class).size());
                    assertEquals(1, context.getBeansOfType(RedisSupport.class).size());
                    assertEquals(1, context.getBeansOfType(RedisInfo.class).size());
                    assertEquals(1, context.getBeansOfType(RedissonClient.class).size());
                    assertEquals(1, context.getBeansOfType(
                            org.springframework.data.redis.stream.StreamMessageListenerContainer.class).size());
                });
    }

    /** 模拟 scan 模式宿主：扫描 com.yomi（组件自身的 @Configuration/@Component 都会被扫到） */
    @org.springframework.context.annotation.ComponentScan("com.yomi.boot.java.component.redis")
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ScanBootstrap {
    }

    /** 宿主自带 RedissonClient 时以宿主的为准（@ConditionalOnMissingBean） */
    @Test
    void hostProvidedRedissonClientWins() {
        RedissonClient stub = proxyClient();
        runner(stub).withPropertyValues("spring.data.redis.host=127.0.0.1")
                .run(context -> assertSame(stub, context.getBean(RedissonClient.class),
                        "宿主自带的 RedissonClient 必须胜出"));
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner */
    private ApplicationContextRunner runner() {
        return runner(proxyClient());
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner */
    private ApplicationContextRunner runner(RedissonClient client) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, RedisAutoConfiguration.class))
                .withBean(RedissonClient.class, () -> client);
    }

    /** 构造 RedissonClient 动态代理桩 */
    private static RedissonClient proxyClient() {
        return (RedissonClient) Proxy.newProxyInstance(RedisAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> null);
    }

    /** 读取 AutoConfiguration.imports 资源内容 */
    private static String readImportsResource() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(IMPORTS_RESOURCE)) {
            assertNotNull(in, "缺少 " + IMPORTS_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
