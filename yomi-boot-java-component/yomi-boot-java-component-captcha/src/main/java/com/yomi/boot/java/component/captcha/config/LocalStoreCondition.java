package com.yomi.boot.java.component.captcha.config;

import com.yomi.boot.java.component.captcha.store.CaptchaStoreType;
import com.yomi.boot.java.component.redis.config.RedisConfiguredCondition;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 本地存储装配条件：{@code yomi.captcha.store=local}，或 store=auto 且未配置 Redis 连接目标
 *
 * @author zwei
 */
public class LocalStoreCondition extends SpringBootCondition {

    /** 复用 redis 组件的「是否配置连接目标」判定（store=auto 时决定走 Redis 还是本地） */
    private final RedisConfiguredCondition delegate = new RedisConfiguredCondition();

    /** 按 {@code yomi.captcha.store} 与 Redis 是否可配决定是否注册本地存储 */
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        CaptchaStoreType store = CaptchaStoreType.parse(
                context.getEnvironment().getProperty("yomi.captcha.store"), CaptchaStoreType.AUTO);
        if (store == CaptchaStoreType.LOCAL) {
            return ConditionOutcome.match(ConditionMessage.forCondition("CaptchaLocalStore")
                    .because("yomi.captcha.store=local（显式指定优先于 Redis 是否可用）"));
        }
        if (store == CaptchaStoreType.REDIS) {
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("CaptchaLocalStore")
                    .because("yomi.captcha.store=redis"));
        }
        // AUTO：配了 Redis 用 redis，否则兜底本地
        if (delegate.getMatchOutcome(context, metadata).isMatch()) {
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("CaptchaLocalStore")
                    .because("已配置 spring.data.redis.* 连接目标，store=auto 时优先 Redis"));
        }
        return ConditionOutcome.match(ConditionMessage.forCondition("CaptchaLocalStore")
                .found("未配置").items("spring.data.redis.*"));
    }

}
