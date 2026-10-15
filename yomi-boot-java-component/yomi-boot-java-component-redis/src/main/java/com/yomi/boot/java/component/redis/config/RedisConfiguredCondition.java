package com.yomi.boot.java.component.redis.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * redis 组件激活条件：{@code spring.data.redis.*} 指向了实际 Redis。
 * <p>
 * 支持 host、url、sentinel、cluster、masterreplica 等写法，配置任一连通目标即激活组件。
 * 使用本条件而非 {@code @ConditionalOnBean(RedisConnectionFactory.class)}，避免在未配置 Redis 时
 * 因 Boot 默认本地连接工厂而误装配。
 * </p>
 *
 * @author zwei
 */
public class RedisConfiguredCondition extends SpringBootCondition {

    /** 连接目标配置键：命中任一即认为宿主配置了 Redis */
    private static final String[] TARGET_KEYS = {
            "spring.data.redis.host",
            "spring.data.redis.url",
            "spring.data.redis.sentinel.master",
            "spring.data.redis.sentinel.nodes",
            "spring.data.redis.cluster.nodes",
            "spring.data.redis.masterreplica.nodes",
    };

    /** 环境是否配置了 Redis 连接目标（非默认 localhost 兜底） */
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        for (String key : TARGET_KEYS) {
            if (StringUtils.isNotBlank(environment.getProperty(key))) {
                return ConditionOutcome.match(ConditionMessage.forCondition("RedisConfigured")
                        .found("spring.data.redis").items(key));
            }
        }
        return ConditionOutcome.noMatch(ConditionMessage.forCondition("RedisConfigured")
                .because("未配置 spring.data.redis.* 连接目标（host/url/sentinel/cluster/masterreplica）"));
    }

}
