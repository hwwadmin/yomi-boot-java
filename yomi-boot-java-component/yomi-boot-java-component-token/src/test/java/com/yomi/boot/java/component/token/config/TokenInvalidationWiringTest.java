package com.yomi.boot.java.component.token.config;

import com.yomi.boot.java.component.token.store.TokenInvalidationChannel;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失效通知的装配契约：<b>复用现成的监听容器</b>，不另建。
 * <p>
 * 早先版本按类型 {@code @ConditionalOnMissingBean} 自建 {@code RedisMessageListenerContainer}——Boot 默认
 * 就注册了同名同类型的容器，自建那个被条件顶掉、结果"谁都没订阅"（条件报告实测：Did not match
 * @ConditionalOnMissingBean found beans of type RedisMessageListenerContainer）。本用例锁住现在的行为：
 * 监听器注册到已存在的容器上；容器缺席时只发不收、不影响装配
 */
class TokenInvalidationWiringTest {

    @Test
    void listenerRegisteredOnExistingContainer() {
        RecordingContainer container = new RecordingContainer();
        runner().withBean("redisMessageListenerContainer", RedisMessageListenerContainer.class, () -> container)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配不应失败: " + context.getStartupFailure());
                    TokenInvalidationChannel channel = context.getBean(TokenInvalidationChannel.class);

                    assertEquals(List.of(channel.getChannel()), container.topics,
                            "必须把监听器注册到现成容器的失效通知通道上（不订阅 = 跨实例失效静默失效）");
                    assertTrue(container.listener instanceof TokenInvalidationChannel,
                            "注册的监听器必须是 token 的失效通知通道");
                });
    }

    @Test
    void assemblySurvivesWithoutAnyListenerContainer() {
        runner().run(context -> {
            assertNull(context.getStartupFailure(), () -> "没有监听容器时装配不应失败: " + context.getStartupFailure());
            assertNotNull(context.getBean(TokenInvalidationChannel.class), "通道 Bean 仍应可用（只发不收）");
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TokenAutoConfiguration.class))
                .withPropertyValues("yomi.token.enabled=true", "spring.application.name=token-notify-wiring",
                        "spring.data.redis.host=127.0.0.1")
                .withBean(StringRedisTemplate.class, TerminalStringRedisTemplate::new)
                .withBean(RedissonClient.class, () -> redissonStub());
    }

    /** 无连接工厂的模板桩：注册为 Bean 会被调 afterPropertiesSet，跳过工厂校验 */
    static class TerminalStringRedisTemplate extends StringRedisTemplate {

        @Override
        public void afterPropertiesSet() {
            // 测试桩不建连
        }
    }

    private static RedissonClient redissonStub() {
        return (RedissonClient) Proxy.newProxyInstance(TokenInvalidationWiringTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> null);
    }

    /** 记录注册行为的容器桩：不走真实建连（覆盖生命周期钩子） */
    static class RecordingContainer extends RedisMessageListenerContainer {

        final List<String> topics = new ArrayList<>();

        MessageListener listener;

        @Override
        public void afterPropertiesSet() {
            // 测试桩不建连
        }

        @Override
        public void start() {
            // 测试桩不建连
        }

        @Override
        public void stop() {
            // 测试桩不建连
        }

        @Override
        public void addMessageListener(MessageListener listener, Topic topic) {
            this.listener = listener;
            this.topics.add(topic.getTopic());
        }
    }
}
