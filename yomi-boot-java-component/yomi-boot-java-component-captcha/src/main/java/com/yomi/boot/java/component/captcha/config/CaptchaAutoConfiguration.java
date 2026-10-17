package com.yomi.boot.java.component.captcha.config;

import com.yomi.boot.java.component.captcha.ICaptchaService;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.generator.ArithmeticCaptchaGenerator;
import com.yomi.boot.java.component.captcha.generator.CaptchaGenerator;
import com.yomi.boot.java.component.captcha.generator.TextCaptchaGenerator;
import com.yomi.boot.java.component.captcha.render.CaptchaRenderer;
import com.yomi.boot.java.component.captcha.render.DefaultCaptchaRenderer;
import com.yomi.boot.java.component.captcha.render.filter.CaptchaFilter;
import com.yomi.boot.java.component.captcha.service.CaptchaService;
import com.yomi.boot.java.component.captcha.store.CaptchaKeys;
import com.yomi.boot.java.component.captcha.store.CaptchaStore;
import com.yomi.boot.java.component.captcha.store.LocalCaptchaStore;
import com.yomi.boot.java.component.captcha.store.RedisCaptchaStore;
import com.yomi.boot.java.component.captcha.web.CaptchaExceptionAdvice;
import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证码组件自动装配
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 注册，
 * 不依赖宿主应用扫描 {@code com.yomi}。仅 {@code yomi.captcha.enabled=true} 时装配；
 * 存储按 {@code yomi.captcha.store}（默认 auto：配了 Redis 用 redis，否则 local）。
 * 组件不提供接口，仅 {@link CaptchaExceptionAdvice} 一个 Web Bean（Servlet 应用）。
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(CaptchaProperties.class)
@ConditionalOnProperty(value = "yomi.captcha.enabled", havingValue = "true")
public class CaptchaAutoConfiguration {

    /** 挑战键前缀：仅 Redis 存储落键时才需要（local 不构造存储键，前缀缺失不应阻断启动） */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(RedisStoreCondition.class)
    public CaptchaKeys captchaKeys(CaptchaProperties properties, Environment environment) {
        return new CaptchaKeys(resolvePrefix(properties, environment));
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(RedisStoreCondition.class)
    public CaptchaStore redisCaptchaStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, CaptchaKeys keys,
                                          CaptchaProperties properties) {
        // 用 ObjectProvider 惰性获取 StringRedisTemplate：Bean 缺失时抛出下面的错误，
        // 报错文案里带上所属组件与两种处理方式，便于按配置项排查
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw new IllegalStateException("yomi.captcha.store=redis 但容器中没有 StringRedisTemplate："
                    + "它由 Boot 的 DataRedisAutoConfiguration 提供，被排除（spring.autoconfigure.exclude）时请显式定义该 Bean，"
                    + "或改为 yomi.captcha.store=local");
        }
        log.info("[Captcha] 挑战存储=redis, 挑战键前缀={}, ttl={}", keys.getPrefix(), properties.getTtl());
        return new RedisCaptchaStore(redisTemplate, keys, properties.getTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(LocalStoreCondition.class)
    public CaptchaStore localCaptchaStore(CaptchaProperties properties) {
        log.info("[Captcha] 挑战存储=local（单实例可用；多实例部署请配置 Redis 或显式 store=redis，"
                + "否则会出现 A 实例签发、B 实例校验失败）");
        return new LocalCaptchaStore(properties.getTtl(), properties.getLocal().getMaxEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    public CaptchaRenderer captchaRenderer(CaptchaProperties properties, ObjectProvider<CaptchaFilter> overlayFilters) {
        return new DefaultCaptchaRenderer(properties, overlayFilters.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public TextCaptchaGenerator textCaptchaGenerator(CaptchaProperties properties) {
        return new TextCaptchaGenerator(properties.getText());
    }

    @Bean
    @ConditionalOnMissingBean
    public ArithmeticCaptchaGenerator arithmeticCaptchaGenerator(CaptchaProperties properties) {
        return new ArithmeticCaptchaGenerator(properties.getArithmetic());
    }

    @Bean
    @ConditionalOnMissingBean
    public ICaptchaService captchaService(List<CaptchaGenerator> generators, CaptchaRenderer renderer,
                                         ObjectProvider<CaptchaStore> storeProvider, CaptchaProperties properties) {
        CaptchaStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("yomi.captcha.store=" + properties.getStore()
                    + " 但未装配验证码存储：store=redis 需要配置 spring.data.redis.host 以启用 redis 组件，或改为 store=local");
        }
        Map<CaptchaType, CaptchaGenerator> index = new LinkedHashMap<>();
        for (CaptchaGenerator generator : generators) {
            CaptchaGenerator exists = index.put(generator.type(), generator);
            if (exists != null) {
                throw new IllegalStateException("验证码类型 " + generator.type() + " 存在多个生成器: "
                        + exists.getClass().getName() + " / " + generator.getClass().getName());
            }
        }
        for (CaptchaType type : CaptchaType.values()) {
            if (!index.containsKey(type)) {
                throw new IllegalStateException("缺少验证码类型 " + type + " 的生成器 Bean");
            }
        }
        log.info("[Captcha] 验证码服务已就绪: 存储={}, ttl={}, 默认类型={}",
                store.name(), properties.getTtl(), properties.getDefaultType());
        return new CaptchaService(index, renderer, store, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public CaptchaExceptionAdvice captchaExceptionAdvice() {
        return new CaptchaExceptionAdvice();
    }

    /** 从环境补全存储键前缀 */
    private static String resolvePrefix(CaptchaProperties properties, Environment environment) {
        String raw = properties.getStorePrefix();
        if (StringUtils.isBlank(raw)) {
            String applicationName = environment.getProperty("spring.application.name");
            if (StringUtils.isBlank(applicationName)) {
                throw new IllegalStateException(
                        "yomi.captcha.store-prefix 未配置，且无法从 spring.application.name 推导，请显式配置 yomi.captcha.store-prefix");
            }
            raw = "yomi:captcha:" + applicationName;
        }
        return raw;
    }

}
