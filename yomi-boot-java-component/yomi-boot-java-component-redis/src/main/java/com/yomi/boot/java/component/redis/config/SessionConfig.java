package com.yomi.boot.java.component.redis.config;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * 启用session-redis配置
 *
 * @author zwei
 */
@Configuration
@EnableRedisHttpSession
@Conditional(RedisConfiguredCondition.class)
public class SessionConfig {

}
