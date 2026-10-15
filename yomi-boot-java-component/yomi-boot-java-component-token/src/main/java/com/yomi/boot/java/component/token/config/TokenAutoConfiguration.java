package com.yomi.boot.java.component.token.config;

import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.generator.TokenGenerator;
import com.yomi.boot.java.component.token.generator.UuidTokenGenerator;
import com.yomi.boot.java.component.token.service.TokenService;
import com.yomi.boot.java.component.token.store.RedisTokenStore;
import com.yomi.boot.java.component.token.store.TokenKeys;
import com.yomi.boot.java.component.token.store.TokenStore;
import com.yomi.boot.java.component.token.web.TokenExceptionAdvice;
import com.yomi.boot.java.component.token.web.TokenInterceptor;
import com.yomi.boot.java.component.token.web.TokenWebMvcConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 令牌服务自动装配
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 注册，
 * 不依赖宿主应用扫描 {@code com.yomi}。仅 {@code yomi.token.enabled=true} 时装配；
 * 启用但缺少 Redis 时启动即失败。单点登录（{@code allow-multi-login=false}）另需 Redisson 签发锁。
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(TokenProperties.class)
@ConditionalOnProperty(value = "yomi.token.enabled", havingValue = "true")
public class TokenAutoConfiguration {

    @Bean
    public TokenKeys tokenKeys(TokenProperties properties, Environment environment) {
        properties.validate();
        TokenKeys keys = new TokenKeys(resolvePrefix(properties, environment));
        log.info("令牌服务已启用: storePrefix={}, ttl={}, allowMultiLogin={}, renewOnValidate={}",
                keys.getPrefix(), properties.getTtl(), properties.isAllowMultiLogin(), properties.isRenewOnValidate());
        return keys;
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenStore tokenStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, TokenKeys keys) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw new IllegalStateException(
                    "yomi.token.enabled=true 需要 Redis：请配置 spring.data.redis.host 以启用 redis 组件");
        }
        return new RedisTokenStore(redisTemplate, keys);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenGenerator tokenGenerator() {
        return new UuidTokenGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ITokenService tokenService(TokenGenerator tokenGenerator, TokenStore tokenStore,
                                     TokenProperties properties, TokenKeys keys,
                                     ObjectProvider<RedissonClient> redissonClientProvider) {
        if (!properties.isAllowMultiLogin() && redissonClientProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.token.allow-multi-login=false 需要 Redisson（签发互斥锁）：请配置 spring.data.redis.host 以启用 redis 组件");
        }
        return new TokenService(tokenGenerator, tokenStore, properties, keys);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public TokenInterceptor tokenInterceptor(ITokenService tokenService) {
        return new TokenInterceptor(tokenService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public TokenExceptionAdvice tokenExceptionAdvice() {
        return new TokenExceptionAdvice();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public TokenWebMvcConfig tokenWebMvcConfig(TokenInterceptor tokenInterceptor, TokenProperties properties) {
        return new TokenWebMvcConfig(tokenInterceptor, properties);
    }

    /** 从环境补全存储键前缀 */
    private static String resolvePrefix(TokenProperties properties, Environment environment) {
        String raw = properties.getStorePrefix();
        if (StringUtils.isBlank(raw)) {
            String applicationName = environment.getProperty("spring.application.name");
            if (StringUtils.isBlank(applicationName)) {
                throw new IllegalStateException(
                        "yomi.token.store-prefix 未配置，且无法从 spring.application.name 推导，请显式配置 yomi.token.store-prefix");
            }
            raw = "yomi:token:" + applicationName;
        }
        return raw;
    }

}
