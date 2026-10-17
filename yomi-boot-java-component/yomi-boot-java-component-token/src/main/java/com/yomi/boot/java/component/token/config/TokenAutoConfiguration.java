package com.yomi.boot.java.component.token.config;

import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.redis.config.RedisConfiguredCondition;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.generator.TokenGenerator;
import com.yomi.boot.java.component.token.generator.UuidTokenGenerator;
import com.yomi.boot.java.component.token.service.TokenService;
import com.yomi.boot.java.component.token.store.RedisTokenStore;
import com.yomi.boot.java.component.token.store.TokenInvalidationChannel;
import com.yomi.boot.java.component.token.store.TokenSessionCache;
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
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

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
        log.info("令牌服务已启用: storePrefix={}, ttl={}, allowMultiLogin={}, renewOnValidate={}, localCacheTtl={}",
                keys.getPrefix(), properties.getTtl(), properties.isAllowMultiLogin(), properties.isRenewOnValidate(),
                properties.getLocalCacheTtl());
        return keys;
    }

    /** 进程内会话缓存（TTL=0 时关闭，读写均为空操作） */
    @Bean
    @ConditionalOnMissingBean
    public TokenSessionCache tokenSessionCache(TokenProperties properties) {
        return new TokenSessionCache(properties.getLocalCacheTtl());
    }

    /**
     * 失效通知通道：删除/替换在 Redis 生效后发布通知，同时作为订阅者接收其它实例的通知。
     * <p>订阅注册放在 {@link #tokenStore} 里做，只在未自定义 TokenStore 且 Redis 已配置时执行：
     * 宿主提供 TokenStore 时以宿主实现为准，不注册失效通知订阅。</p>
     * <p>发布失败只记 WARN：撤销已生效，其它实例按条目 TTL 到期后失效</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenInvalidationChannel tokenInvalidationChannel(StringRedisTemplate redisTemplate, TokenKeys keys,
                                                            TokenSessionCache sessionCache) {
        return new TokenInvalidationChannel(redisTemplate, keys.invalidateChannel(), sessionCache);
    }

    /**
     * 默认令牌存储（Redis）。
     * <p>
     * Redis 可用性判定复用 redis 组件的 {@link RedisConfiguredCondition#isConfigured}：
     * 只看 {@code StringRedisTemplate} 是否存在并不够——Boot 默认也会注册指向 localhost 的模板，
     * 只按模板判断会装配成功但每个请求报 500。
     * 宿主自定义 {@link TokenStore} Bean 时以宿主实现为准（{@code @ConditionalOnMissingBean}）
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenStore tokenStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, TokenKeys keys,
                                 TokenSessionCache sessionCache, TokenInvalidationChannel invalidationChannel,
                                 ObjectProvider<RedisMessageListenerContainer> listenerContainers,
                                 Environment environment) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null || !RedisConfiguredCondition.isConfigured(environment)) {
            throw new IllegalStateException("yomi.token.enabled=true 需要 Redis：请配置 spring.data.redis.host/url/"
                    + "sentinel/cluster 任一以启用 redis 组件（Boot 默认的 localhost 模板不算），"
                    + "或自定义 TokenStore Bean 覆盖");
        }
        registerInvalidationListener(invalidationChannel, listenerContainers);
        return new RedisTokenStore(redisTemplate, keys, sessionCache, invalidationChannel);
    }

    /**
     * 把失效通知订阅注册到现成的 {@code RedisMessageListenerContainer}（Boot 默认会注册一个，不另建容器）。
     * 容器有多个（宿主自建 + Boot 默认）时不做猜测，该实例只发不收。
     * <p>容器建连失败会让上下文启动失败，这是启用 token 却连不上 Redis 时期望的行为：
     * 启动失败比启动成功但每个请求报 500 更易发现</p>
     */
    private void registerInvalidationListener(TokenInvalidationChannel channel,
                                              ObjectProvider<RedisMessageListenerContainer> listenerContainers) {
        if (!channel.isEnabled()) {
            log.info("[token] 进程内缓存已关闭(local-cache-ttl=0)，不注册失效通知订阅");
            return;
        }
        RedisMessageListenerContainer container = listenerContainers.getIfUnique();
        if (container == null) {
            log.info("[token] 未找到唯一的 RedisMessageListenerContainer，失效通知只发不收（其它实例最迟在条目 TTL 内生效）");
            return;
        }
        container.addMessageListener(channel, new ChannelTopic(channel.getChannel()));
        log.info("[token] 失效通知订阅已注册: channel={}", channel.getChannel());
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
                    "yomi.token.allow-multi-login=false 需要 Redisson（签发互斥锁）：请配置 spring.data.redis.host/url/"
                            + "sentinel/cluster 任一以启用 redis 组件，或自定义 TokenStore + allow-multi-login=true");
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
