package com.yomi.boot.java.component.desensitize;

import com.yomi.boot.java.component.desensitize.config.DesensitizeAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏服务装配测试：内置策略都是 Bean，宿主新增策略 / 同名 Bean 启动失败 / 策略名冲突 / 自定义服务
 *
 * @author zwei
 */
class DesensitizeAutoConfigurationTest {

    /** 宿主新增策略：新 Bean 名 + 新策略名 */
    @Configuration(proxyBeanMethods = false)
    static class CustomStrategyHost {

        @Bean
        public DesensitizeStrategy studentNoDesensitizeStrategy() {
            return new KeepEdgeDesensitizeStrategy("STUDENT_NO", 4, 2);
        }

    }

    /** 宿主声明与内置同名的 Bean：不支持同名替换 */
    @Configuration(proxyBeanMethods = false)
    static class SameBeanNameHost {

        @Bean
        public DesensitizeStrategy mobileDesensitizeStrategy() {
            return new KeepEdgeDesensitizeStrategy(DesensitizeType.MOBILE.name(), 3, 2);
        }

    }

    /** 宿主策略名与内置重名但 Bean 名不同：策略名冲突 */
    @Configuration(proxyBeanMethods = false)
    static class DuplicateNameHost {

        @Bean
        public DesensitizeStrategy myMobileStrategy() {
            return new KeepEdgeDesensitizeStrategy(DesensitizeType.MOBILE.name(), 3, 2);
        }

    }

    /** 宿主自建策略中心：策略集合由宿主定义，不校验内置齐备 */
    @Configuration(proxyBeanMethods = false)
    static class CustomRegistryHost {

        @Bean
        public DesensitizeStrategyRegistry customDesensitizeStrategyRegistry() {
            return new DesensitizeStrategyRegistry(Map.of("ONLY_ONE",
                    new KeepEdgeDesensitizeStrategy("ONLY_ONE", 1, 0)));
        }

    }

    /** 宿主自定义服务实现 */
    static class CustomIDesensitizeService implements IDesensitizeService {

        @Override
        public String mask(String type, String value) {
            return "CUSTOM";
        }

    }

    /** 模拟宿主：仅靠自动装配引入，不扫描 com.yomi */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(DesensitizeAutoConfiguration.class)
    static class AutoConfigHost {
    }

    /** 宿主手写 @ComponentScan（无 @SpringBootApplication 的自动装配排除）：会同时扫到配置类与策略类 */
    @ComponentScan({"com.yomi.boot.java.component.desensitize.config", "com.yomi.boot.java.component.desensitize.strategy"})
    @Configuration(proxyBeanMethods = false)
    static class ScanBootstrap {
    }

    /** 模拟宿主自行实现服务并注册 Bean */
    @Configuration(proxyBeanMethods = false)
    static class CustomServiceImplHost {

        @Bean
        public IDesensitizeService customIDesensitizeService() {
            return new CustomIDesensitizeService();
        }

    }

    @Test
    void defaultBeans_wired() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class))
                .run(context -> {
                    assertEquals(DesensitizeType.values().length, context.getBeansOfType(DesensitizeStrategy.class).size());
                    assertEquals("138****5678", context.getBean(IDesensitizeService.class)
                            .mask(DesensitizeType.MOBILE.name(), "13812345678"));
                });
    }

    @Test
    void customStrategy_collected() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class))
                .withUserConfiguration(CustomStrategyHost.class)
                .run(context -> {
                    IDesensitizeService service = context.getBean(IDesensitizeService.class);
                    assertEquals("2024*01", service.mask("STUDENT_NO", "2024001"));
                    assertEquals(DesensitizeType.values().length + 1,
                            context.getBeansOfType(DesensitizeStrategy.class).size());
                });
    }

    @Test
    void duplicateStrategyName_failsFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class))
                .withUserConfiguration(DuplicateNameHost.class)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage();
                    assertTrue(message.contains("脱敏策略名称重复"), message);
                });
    }

    @Test
    void sameBeanNameAsBuiltin_failsFast() {
        // 不支持"同名实现替换内置"：同名 Bean 启动即失败（本上下文由策略名重复校验兜住；
        // 宿主连策略包一起扫时则是 Bean 定义重名，真实 Boot 应用两种形态均已实测）
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class))
                .withUserConfiguration(SameBeanNameHost.class)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage();
                    assertTrue(message.contains("mobileDesensitizeStrategy"), message);
                });
    }

    @Test
    void customRegistryBean_skipsBuiltinCheck() {
        // 宿主自建策略中心：策略集合由宿主定义，不强制内置齐备
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class))
                .withUserConfiguration(CustomRegistryHost.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("1***", context.getBean(IDesensitizeService.class).mask("ONLY_ONE", "1234"));
                });
    }

    @Test
    void defaultImplRegistered_whenNoCustomBean() {
        try (var context = context(AutoConfigHost.class)) {
            IDesensitizeService service = context.getBean(IDesensitizeService.class);
            assertInstanceOf(DesensitizeService.class, service);
            assertEquals("138****5678", service.mask(DesensitizeType.MOBILE.name(), "13812345678"));
        }
    }

    @Test
    void customServiceBean_wins_regardlessOfRegistrationOrder() {
        List<Class<?>[]> orders = List.of(
                new Class<?>[]{CustomServiceImplHost.class, AutoConfigHost.class},
                new Class<?>[]{AutoConfigHost.class, CustomServiceImplHost.class});
        for (Class<?>[] order : orders) {
            try (var context = context(order)) {
                assertEquals(1, context.getBeanNamesForType(IDesensitizeService.class).length);
                IDesensitizeService service = context.getBean(IDesensitizeService.class);
                assertInstanceOf(CustomIDesensitizeService.class, service);
                assertEquals("CUSTOM", service.mask(DesensitizeType.MOBILE.name(), "13812345678"));
            }
        }
    }

    @Test
    void importsFile_loadedByBoot() {
        // 用 Boot 自身的解析器读取 imports 文件，校验生产装配路径可达
        ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());
        assertTrue(candidates.getCandidates().contains(DesensitizeAutoConfiguration.class.getName()));
    }

    @Test
    void componentScanOnly_wiresDefaultBeans() {
        // 只有扫描通道（宿主未走自动装配）时同样装配——这条保证下面的双通道用例不是空跑
        new ApplicationContextRunner()
                .withUserConfiguration(ScanBootstrap.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(DesensitizeService.class, context.getBean(IDesensitizeService.class));
                    assertInstanceOf(DesensitizeStrategyRegistry.class, context.getBean(DesensitizeStrategyRegistry.class));
                });
    }

    @Test
    void componentScanAndImportsRegisterExactlyOneBean() {
        // 扫描 + imports 双通道同时命中同一配置类：各 Bean 只管一份（策略 Bean 靠 @ConditionalOnMissingBean(name) 去重）
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class))
                .withUserConfiguration(ScanBootstrap.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(DesensitizeStrategyRegistry.class).size());
                    assertEquals(1, context.getBeansOfType(IDesensitizeService.class).size());
                    assertEquals(DesensitizeType.values().length, context.getBeansOfType(DesensitizeStrategy.class).size());
                });
    }

    private static AnnotationConfigApplicationContext context(Class<?>... configs) {
        var context = new AnnotationConfigApplicationContext();
        context.register(configs);
        context.refresh();
        return context;
    }

}
