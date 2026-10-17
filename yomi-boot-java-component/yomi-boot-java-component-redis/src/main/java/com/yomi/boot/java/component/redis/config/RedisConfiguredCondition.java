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
 * 本条件只读取连接目标属性；{@code @ConditionalOnBean(RedisConnectionFactory.class)} 在未配置 Redis 时
 * 会因 Boot 默认的本地连接工厂而成立，与宿主是否配置 Redis 无关。
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

    /** 环境是否显式配置了 Redis 连接目标 */
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String matched = firstConfiguredTarget(context.getEnvironment());
        if (matched != null) {
            return ConditionOutcome.match(ConditionMessage.forCondition("RedisConfigured")
                    .found("spring.data.redis").items(matched));
        }
        return ConditionOutcome.noMatch(ConditionMessage.forCondition("RedisConfigured")
                .because("未配置 spring.data.redis.* 连接目标（host/url/sentinel/cluster/masterreplica）"));
    }

    /**
     * 环境是否配置了 Redis 连接目标。
     * <p>
     * 供其它组件复用同一判定（键列表集中维护）：<b>判定依据是配置属性，{@code StringRedisTemplate}/{@code RedisConnectionFactory}
     * 是否存在与配置无关</b>——Boot 默认注册指向 localhost 的工厂与模板，按 Bean 判断会恒为真（组件看似装配成功，实际每请求报错）
     * </p>
     */
    public static boolean isConfigured(Environment environment) {
        return firstConfiguredTarget(environment) != null;
    }

    /** 返回首个命中的连接目标配置键，未命中返回 null */
    private static String firstConfiguredTarget(Environment environment) {
        for (String key : TARGET_KEYS) {
            if (StringUtils.isNotBlank(environment.getProperty(key))) {
                return key;
            }
        }
        return null;
    }

}
