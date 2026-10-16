package com.yomi.boot.java.component.redis.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * 启用 session-redis 配置
 * <p>
 * Spring Session 的 HTTP Session 是 servlet 特性（{@code SessionRepositoryFilter} 依赖 jakarta.servlet）：
 * 仅在 servlet Web 应用装配——纯 WebFlux/非 Web 宿主（类路径无 servlet API）下跳过本配置，避免启动失败
 *
 * @author zwei
 */
@Configuration
@EnableRedisHttpSession
@Conditional(RedisConfiguredCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionConfig {

}
