package com.yomi.boot.java.component.token.config;

import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.web.TokenExceptionAdvice;
import com.yomi.boot.java.component.token.web.TokenInterceptor;
import com.yomi.boot.java.component.token.web.TokenWebMvcConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * token 的 Web Bean 只在 Servlet 应用注册：
 * 非 Web / WebFlux 应用不注册这些 Bean，不会给出"令牌校验在生效"的错误印象
 */
class TokenWebBeansConditionTest {

    @Test
    void webBeansAbsentInNonWebApplication() {
        runner().run(context -> {
            assertNull(context.getStartupFailure(), () -> "非 Web 应用装配不应失败: " + context.getStartupFailure());
            assertNotNull(context.getBean(ITokenService.class), "非 Web 应用令牌服务（签发/校验 API）仍应可用");
            assertFalse(context.containsBean("tokenInterceptor"), "非 Web 应用不应注册令牌拦截器");
            assertFalse(context.containsBean("tokenExceptionAdvice"));
            assertFalse(context.containsBean("tokenWebMvcConfig"));
        });
    }

    @Test
    void webBeansPresentInServletApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TokenAutoConfiguration.class))
                .withPropertyValues("yomi.token.enabled=true", "spring.application.name=token-web-test",
                        "spring.data.redis.host=127.0.0.1")
                .withBean(StringRedisTemplate.class, TokenWebBeansConditionTest::stubRedisTemplate)
                .withBean(RedissonClient.class, () -> proxy(RedissonClient.class))
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "Servlet 应用装配不应失败: " + context.getStartupFailure());
                    assertTrue(context.getBean(TokenInterceptor.class) != null);
                    assertTrue(context.getBean(TokenExceptionAdvice.class) != null);
                    assertTrue(context.getBean(TokenWebMvcConfig.class) != null);
                });
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TokenAutoConfiguration.class))
                .withPropertyValues("yomi.token.enabled=true", "spring.application.name=token-nonweb-test",
                        "spring.data.redis.host=127.0.0.1")
                .withBean(StringRedisTemplate.class, TokenWebBeansConditionTest::stubRedisTemplate)
                .withBean(RedissonClient.class, () -> proxy(RedissonClient.class));
    }

    /** 不触达真实连接的模板桩：tokenStore 只持有引用（连接目标属性另行配置——只给模板 Bean 不算"配了 Redis"） */
    private static StringRedisTemplate stubRedisTemplate() {
        return new StringRedisTemplate(proxy(RedisConnectionFactory.class));
    }

    /** 从 ApplicationContext 取 Bean 的 ObjectProvider */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(TokenWebBeansConditionTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> null);
    }

}
