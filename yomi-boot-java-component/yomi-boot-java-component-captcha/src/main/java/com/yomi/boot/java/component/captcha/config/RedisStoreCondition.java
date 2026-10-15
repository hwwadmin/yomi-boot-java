package com.yomi.boot.java.component.captcha.config;

import com.yomi.boot.java.component.captcha.store.CaptchaStoreType;
import com.yomi.boot.java.component.redis.config.RedisConfiguredCondition;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Redis 存储装配条件：{@code yomi.captcha.store=redis}（或 auto）且配置了 Redis 连接目标
 * <p>
 * 判定复用 redis 组件的 {@link RedisConfiguredCondition}（委托，不复制其配置键列表）：
 * 不能用 {@code StringRedisTemplate} 是否存在来判定——Boot 默认的 localhost 工厂会让它恒为真。
 *
 * @author zwei
 */
public class RedisStoreCondition extends SpringBootCondition {

    /** 委托 {@link RedisConfiguredCondition} 判断环境是否配置了 Redis 连接目标 */
    private final RedisConfiguredCondition delegate = new RedisConfiguredCondition();

    /** store 非 local 时，仅在 Redis 连接目标已配置时匹配 */
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        CaptchaStoreType store = CaptchaStoreType.parse(
                context.getEnvironment().getProperty("yomi.captcha.store"), CaptchaStoreType.AUTO);
        if (store == CaptchaStoreType.LOCAL) {
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("CaptchaRedisStore")
                    .because("yomi.captcha.store=local"));
        }
        return delegate.getMatchOutcome(context, metadata);
    }

}
