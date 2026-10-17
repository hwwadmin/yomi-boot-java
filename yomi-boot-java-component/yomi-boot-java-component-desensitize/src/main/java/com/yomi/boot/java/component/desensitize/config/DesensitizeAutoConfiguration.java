package com.yomi.boot.java.component.desensitize.config;

import com.yomi.boot.java.component.desensitize.service.DesensitizeService;
import com.yomi.boot.java.component.desensitize.IDesensitizeService;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategyRegistry;
import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.strategy.AddressDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.AllDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.BankCardDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.CarNoDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.EmailDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.FixedPhoneDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.IdCardDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.MobileDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.NameDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.PassportDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.PasswordDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.UsernameDesensitizeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 脱敏服务装配
 * <p>
 * 以自动装配（{@code META-INF/spring/...AutoConfiguration.imports}）注册，宿主无需扫描 {@code com.yomi}。
 * 内置策略按 auto-configuration 推荐方式用 {@link Import} 显式列出（不依赖组件扫描）；策略类同时标注
 * {@code @Component}，宿主连 {@code com.yomi} 一起扫到也是同一份 Bean 定义。宿主新增策略
 * 只需声明 {@link DesensitizeStrategy} Bean（新策略名），策略中心从容器取全部实例；内置策略 Bean 不可同名替换。
 * 装配期校验：内置名称齐备、策略名非空且唯一，违反即启动失败。
 * 宿主自行实现 {@link IDesensitizeService} 并注册为 Bean 后，此处的默认实现不装配。手写 {@code @ComponentScan}
 * 扫描 {@code com.yomi} 的宿主（无 {@code @SpringBootApplication} 的自动装配排除）覆盖时建议用 {@code @Component}
 * 实现类，与自动装配的定义一致，不会出现双 Bean。
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@Import({
        AddressDesensitizeStrategy.class,
        AllDesensitizeStrategy.class,
        BankCardDesensitizeStrategy.class,
        CarNoDesensitizeStrategy.class,
        EmailDesensitizeStrategy.class,
        FixedPhoneDesensitizeStrategy.class,
        IdCardDesensitizeStrategy.class,
        MobileDesensitizeStrategy.class,
        NameDesensitizeStrategy.class,
        PassportDesensitizeStrategy.class,
        PasswordDesensitizeStrategy.class,
        UsernameDesensitizeStrategy.class})
public class DesensitizeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DesensitizeStrategyRegistry desensitizeStrategyRegistry(Map<String, DesensitizeStrategy> strategyBeans) {
        DesensitizeStrategyRegistry registry = new DesensitizeStrategyRegistry(strategyBeans);
        requireBuiltinStrategies(registry);
        log.info("脱敏服务已启用: 策略 {} 个: {}", registry.names().size(), registry.names());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public IDesensitizeService desensitizeService(DesensitizeStrategyRegistry registry) {
        return new DesensitizeService(registry);
    }

    /** 校验内置策略名称齐备，缺失即视为异常装配，启动失败；包级可见供测试直接校验 */
    static void requireBuiltinStrategies(DesensitizeStrategyRegistry registry) {
        List<String> missing = Arrays.stream(DesensitizeType.values())
                .map(Enum::name)
                .filter(name -> !registry.names().contains(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺少内置脱敏策略: " + missing + "（已注册: " + registry.names()
                    + "）；内置策略 Bean 不应被替换，自定义规则请新增策略名，"
                    + "或替换 DesensitizeStrategyRegistry Bean 自定义策略集合");
        }
    }

}
