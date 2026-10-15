package com.yomi.boot.java.component.redis.config;

import com.yomi.boot.java.component.redis.annotation.RequestLimitAop;
import com.yomi.boot.java.component.redis.ops.RedisSupport;
import com.yomi.boot.java.component.redis.ops.stream.StreamConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * redis 组件自动装配
 * <p>
 * 通过 {@code META-INF/spring/...AutoConfiguration.imports} 注册，宿主无需扫描
 * {@code com.yomi}（继续扫描也不冲突：Spring 对同一配置类去重）。
 * 各 Bean 受 {@link RedisConfiguredCondition} 等条件约束：须配置有效的
 * {@code spring.data.redis.*} 连接目标后才会装配（含 host、url、sentinel、cluster 等写法）。
 *
 * @author zwei
 */
@AutoConfiguration
// Boot 的连接工厂（Lettuce/Jedis）由 DataRedisAutoConfiguration @Import 注册，
// 排在它之后 @ConditionalOnBean(RedisConnectionFactory.class) 才能看到工厂定义
@AutoConfigureAfter(DataRedisAutoConfiguration.class)
@Import({
        RedisConfig.class,
        RedissonConfig.class,
        SessionConfig.class,
        StreamConfiguration.class,
        RateLimitConfig.class,
        RedisSupport.class,
        RedisInfo.class,
        RequestLimitAop.class,
})
public class RedisAutoConfiguration {

}
