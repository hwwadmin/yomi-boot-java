package com.yomi.boot.java.core.boot.config;

import com.yomi.boot.java.core.aop.CostTimeAop;
import com.yomi.boot.java.core.bc.BCryptPasswordEncoder;
import com.yomi.boot.java.core.bc.PasswordEncoder;
import com.yomi.boot.java.core.boot.cors.CorsConfig;
import com.yomi.boot.java.core.boot.cors.CorsProperties;
import com.yomi.boot.java.core.boot.id.IdProperties;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core 装配契约：imports 文件可达、能力全部经自动装配就绪、
 * 宿主的 {@code @Bean} 优先（自动装配阶段晚于宿主注册）、扫描 + imports 双通道各 Bean 只一份
 */
class CoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoreAutoConfiguration.class));

    /** 宿主把 com.yomi 一起扫到的形态（含 core 的配置类与实现类），与自动装配双通道同时命中 */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.yomi.boot.java.core")
    static class ScanChannelHost {
    }

    @Test
    void importsFileLoadableByBoot() {
        // 用 Boot 自身的解析器读取 imports 文件，校验生产装配路径可达
        ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());
        assertTrue(candidates.getCandidates().contains(CoreAutoConfiguration.class.getName()));
    }

    @Test
    void coreBeansRegistered() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertInstanceOf(BCryptPasswordEncoder.class, context.getBean(PasswordEncoder.class));
            assertNotNull(context.getBean(JacksonJsonHttpMessageConverter.class));
            assertNotNull(context.getBean(GlobalExceptionAdvice.class));
            assertNotNull(context.getBean(CostTimeAop.class));
            assertNotNull(context.getBean(CorsConfig.class));
            assertNotNull(context.getBean(ResourceConfig.class));
            assertNotNull(context.getBean(SpringUtils.class));
            assertEquals(1, context.getBeansOfType(CorsProperties.class).size());
            assertEquals(1, context.getBeansOfType(IdProperties.class).size());
        });
    }

    @Test
    void customPasswordEncoderWins() {
        runner.withBean(PasswordEncoder.class, () -> new PasswordEncoder() {
                    @Override
                    public String encode(CharSequence rawPassword) {
                        return "custom";
                    }

                    @Override
                    public boolean matches(CharSequence rawPassword, String encodedPassword) {
                        return false;
                    }
                })
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(PasswordEncoder.class).size());
                    assertEquals("custom", context.getBean(PasswordEncoder.class).encode("raw"));
                });
    }

    @Test
    void scanAndImportsRegisterExactlyOneBean() {
        runner.withUserConfiguration(ScanChannelHost.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(PasswordEncoder.class).size());
            assertEquals(1, context.getBeansOfType(JacksonJsonHttpMessageConverter.class).size());
            assertEquals(1, context.getBeansOfType(GlobalExceptionAdvice.class).size());
            assertEquals(1, context.getBeansOfType(CostTimeAop.class).size());
            assertEquals(1, context.getBeansOfType(SpringUtils.class).size());
            assertEquals(1, context.getBeansOfType(CorsProperties.class).size());
            assertEquals(1, context.getBeansOfType(IdProperties.class).size());
        });
    }

}
