package com.yomi.boot.java.component.captcha.config;

import com.yomi.boot.java.component.captcha.ICaptchaService;
import com.yomi.boot.java.component.captcha.store.CaptchaKeys;
import com.yomi.boot.java.component.captcha.store.CaptchaStore;
import com.yomi.boot.java.component.captcha.store.LocalCaptchaStore;
import com.yomi.boot.java.component.captcha.store.RedisCaptchaStore;
import com.yomi.boot.java.component.captcha.web.CaptchaExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 装配契约：开关、存储选择（auto/local/redis）、fail-fast、Web Bean 只在 Servlet 应用注册
 */
class CaptchaAutoConfigurationTest {

    @Test
    void disabledByDefault() {
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("captchaService"), "未启用不应装配任何 Bean");
        });
    }

    @Test
    void enabledWithoutRedisUsesLocalStore() {
        runner("yomi.captcha.enabled=true").run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertInstanceOf(LocalCaptchaStore.class, context.getBean(CaptchaStore.class));
            assertNotNull(context.getBean(ICaptchaService.class));
            assertFalse(context.containsBean("captchaExceptionAdvice"), "非 Web 应用不注册 Web Bean");
        });
    }

    @Test
    void enabledWithRedisUsesRedisStore() {
        runner("yomi.captcha.enabled=true", "spring.data.redis.host=127.0.0.1")
                .withBean(StringRedisTemplate.class, CaptchaAutoConfigurationTest::stubRedisTemplate)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertInstanceOf(RedisCaptchaStore.class, context.getBean(CaptchaStore.class));
                });
    }

    @Test
    void explicitLocalStoreWinsOverConfiguredRedis() {
        runner("yomi.captcha.enabled=true", "spring.data.redis.host=127.0.0.1", "yomi.captcha.store=local")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertInstanceOf(LocalCaptchaStore.class, context.getBean(CaptchaStore.class));
                });
    }

    @Test
    void explicitRedisStoreWithoutRedisFailsAtStartup() {
        runner("yomi.captcha.enabled=true", "yomi.captcha.store=redis").run(context -> {
            assertNotNull(context.getStartupFailure(), "store=redis 但未配 Redis 必须启动失败");
            assertTrue(rootMessage(context.getStartupFailure()).contains("未装配验证码存储"),
                    rootMessage(context.getStartupFailure()));
        });
    }

    @Test
    void localStoreNeedsNoPrefixOrApplicationName() {
        runnerWithoutAppName("yomi.captcha.enabled=true").run(context -> {
            assertNull(context.getStartupFailure(), () -> "local 分支不落键，缺前缀与 spring.application.name 不应阻断启动: "
                    + context.getStartupFailure());
            assertInstanceOf(LocalCaptchaStore.class, context.getBean(CaptchaStore.class));
            assertNotNull(context.getBean(ICaptchaService.class));
        });
    }

    @Test
    void redisStoreWithoutPrefixOrApplicationNameFailsAtStartup() {
        runnerWithoutAppName("yomi.captcha.enabled=true", "spring.data.redis.host=127.0.0.1")
                .withBean(StringRedisTemplate.class, CaptchaAutoConfigurationTest::stubRedisTemplate)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "redis 存储要落键，键前缀推导失败必须启动失败");
                    assertTrue(rootMessage(context.getStartupFailure()).contains("store-prefix"),
                            rootMessage(context.getStartupFailure()));
                });
    }

    @Test
    void illegalArithmeticConfigFailsAtStartup() {
        runner("yomi.captcha.enabled=true", "yomi.captcha.arithmetic.operand-min=9",
                "yomi.captcha.arithmetic.operand-max=9", "yomi.captcha.arithmetic.max-result=4")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "合法表达式过少必须启动失败");
                    assertTrue(rootMessage(context.getStartupFailure()).contains("合法表达式"),
                            rootMessage(context.getStartupFailure()));
                });
    }

    @Test
    void illegalConfigFailsEvenWhenKeysBeanOverridden() {
        runner("yomi.captcha.enabled=true", "yomi.captcha.image.font-size=200")
                .withBean(CaptchaKeys.class, () -> new CaptchaKeys("host:captcha:test"))
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "非法图片配置必须启动失败（属性绑定即自检，不依赖 captchaKeys 是否被顶替）");
                    assertTrue(rootMessage(context.getStartupFailure()).contains("font-size"),
                            rootMessage(context.getStartupFailure()));
                });
    }

    @Test
    void adviceRegisteredOnlyInServletApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CaptchaAutoConfiguration.class))
                .withPropertyValues("yomi.captcha.enabled=true", "spring.application.name=captcha-web-test")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertNotNull(context.getBean(ICaptchaService.class));
                    assertNotNull(context.getBean(CaptchaExceptionAdvice.class));
                });
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner */
    private ApplicationContextRunner runner(String... extraProperties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CaptchaAutoConfiguration.class))
                .withPropertyValues(Stream.concat(Stream.of("spring.application.name=captcha-test"),
                        Arrays.stream(extraProperties)).toArray(String[]::new));
    }

    /** 不含 spring.application.name 的上下文（验证键前缀只在 Redis 分支推导） */
    private ApplicationContextRunner runnerWithoutAppName(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CaptchaAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    /** 不触达真实连接的模板桩：存储 Bean 只持有引用 */
    private static StringRedisTemplate stubRedisTemplate() {
        return new StringRedisTemplate((RedisConnectionFactory) Proxy.newProxyInstance(
                CaptchaAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class}, (proxy, method, args) -> null));
    }

    /** 取根因消息（Bean 创建异常会包一层） */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? String.valueOf(error) : current.getMessage();
    }

}
