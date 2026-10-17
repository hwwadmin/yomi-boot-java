package com.yomi.boot.java.component.token.config;

import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.session.TokenSession;
import com.yomi.boot.java.component.token.store.RedisTokenStore;
import com.yomi.boot.java.component.token.store.TokenStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌存储的"缺 Redis 启动即失败"契约：
 * <ol>
 *   <li>判定 Redis 可用性时只看 {@link StringRedisTemplate} 是否存在并不够——Boot 默认也会注册指向 localhost 的模板，
 *       只按模板判断会装配成功但每个请求报 500；</li>
 *   <li>未配置 {@code spring.data.redis.*} 连接目标时启动失败，报错点名组件与出路；</li>
 *   <li>配置了连接目标时装配 {@link RedisTokenStore}；</li>
 *   <li>宿主自定义 {@link TokenStore} 时以宿主实现为准，且不校验 Redis 连接目标。</li>
 * </ol>
 */
class TokenStoreWiringTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                        RedisAutoConfiguration.class, TokenAutoConfiguration.class))
                .withPropertyValues("yomi.token.enabled=true", "yomi.token.store-prefix=yomi:token:test",
                        // 多端登录打开：此处没有其它检查，本判定是唯一的 Redis 可用性依据
                        "yomi.token.allow-multi-login=true")
                // 失效通知会注册到监听容器上；用桩容器让离线上下文不去真连 Redis（真实容器建连失败会让启动失败，
                // 那是生产环境期望的行为，离线用例用桩容器绕开）
                .withBean("redisMessageListenerContainer", RedisMessageListenerContainer.class,
                        TokenInvalidationWiringTest.RecordingContainer::new);
    }

    @Test
    void enabledWithoutRedisTargetFailsFastEvenThoughDefaultTemplateExists() {
        // 前提：Boot 未配置时也会注册指向 localhost 的默认模板，只看模板是否存在会误判 Redis 可用。
        // 单独跑一个只含 DataRedisAutoConfiguration 的上下文验证该前提（失败的上下文不能再取 Bean）
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .run(context -> assertTrue(context.getBeanNamesForType(StringRedisTemplate.class).length > 0,
                        "前提：Boot 未配置时也会注册默认 StringRedisTemplate"));

        runner().run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "未配置 Redis 连接目标时必须启动失败（旧行为：装配成功、每请求 500）");
            assertTrue(messages(context.getStartupFailure()).contains("yomi.token.enabled=true 需要 Redis"),
                    "报错必须点名组件与出路: " + messages(context.getStartupFailure()));
            assertTrue(messages(context.getStartupFailure()).contains("TokenStore"),
                    "报错应给出逃生口（自定义 TokenStore）: " + messages(context.getStartupFailure()));
        });
    }

    @Test
    void enabledWithRedisTargetAssemblesRedisStore() {
        runner().withPropertyValues("spring.data.redis.host=127.0.0.1")
                .withBean(RedissonClient.class, TokenStoreWiringTest::proxyClient)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "配了连接目标不应失败: " + context.getStartupFailure());
                    assertTrue(context.getBean(TokenStore.class) instanceof RedisTokenStore,
                            "配了连接目标必须装配 Redis 存储");
                    assertNotNull(context.getBean(ITokenService.class));
                });
    }

    @Test
    void hostProvidedStoreWinsAndNeedsNoRedisTarget() {
        TokenStore hostStore = new NoopTokenStore();
        runner().withBean(TokenStore.class, () -> hostStore).run(context -> {
            assertNull(context.getStartupFailure(),
                    () -> "宿主自定义 TokenStore 时不得被 Redis 判定拦住: " + context.getStartupFailure());
            assertSame(hostStore, context.getBean(TokenStore.class));
        });
    }

    /** 遍历异常链拼接消息（启动失败常被 BeanCreationException 包装） */
    private static String messages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            builder.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return builder.toString();
    }

    /** 不触达真实连接的 RedissonClient 桩 */
    private static RedissonClient proxyClient() {
        return (RedissonClient) Proxy.newProxyInstance(TokenStoreWiringTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> null);
    }

    /** 宿主自带存储的最小实现 */
    static class NoopTokenStore implements TokenStore {

        @Override
        public void save(String token, TokenSession session) {
        }

        @Override
        public TokenSession load(String token) {
            return null;
        }

        @Override
        public void delete(String token) {
        }

        @Override
        public void deleteByUserId(Long userId) {
        }

        @Override
        public void replaceAllForUser(String token, TokenSession session) {
        }

        @Override
        public boolean renewIfPresent(String token, TokenSession session) {
            return false;
        }

        @Override
        public List<TokenSession> listByUserId(Long userId) {
            return List.of();
        }
    }

}
