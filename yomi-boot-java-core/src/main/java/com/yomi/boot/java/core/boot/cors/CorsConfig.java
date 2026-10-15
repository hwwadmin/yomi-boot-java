package com.yomi.boot.java.core.boot.cors;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * cors配置
 *
 * @author zwei
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class CorsConfig implements WebMvcConfigurer {

    @Resource
    protected CorsProperties corsProperties;

    /**
     * 设置跨域，按 yml 配置 yomi.cors.* 生成；未启用时不注册，浏览器执行同源策略
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (Objects.isNull(corsProperties) || !corsProperties.isEnabled()) {
            log.info("未启用跨域配置[yomi.cors.enabled=false]，浏览器将执行同源策略");
            return;
        }
        if (corsProperties.isAllowCredentials() && corsProperties.getAllowedOrigins().contains("*")) {
            throw new IllegalStateException(
                    "yomi.cors.allow-credentials=true 时 yomi.cors.allowed-origins 不能包含 *，请改用 yomi.cors.allowed-origin-patterns");
        }

        List<String> origins = new ArrayList<>(corsProperties.getAllowedOrigins());
        List<String> patterns = new ArrayList<>(corsProperties.getAllowedOriginPatterns());
        if (origins.isEmpty() && patterns.isEmpty()) {
            // 未显式配置来源时保持历史行为：全局放行
            if (corsProperties.isAllowCredentials()) {
                patterns.add("*");
            } else {
                origins.add("*");
            }
        }

        CorsRegistration registration = registry.addMapping(corsProperties.getPathPattern());
        if (!origins.isEmpty()) {
            registration.allowedOrigins(origins.toArray(String[]::new));
        }
        if (!patterns.isEmpty()) {
            registration.allowedOriginPatterns(patterns.toArray(String[]::new));
        }
        registration.allowedMethods(corsProperties.getAllowedMethods().toArray(String[]::new))
                .allowedHeaders(corsProperties.getAllowedHeaders().toArray(String[]::new))
                .allowCredentials(corsProperties.isAllowCredentials())
                .maxAge(corsProperties.getMaxAge());
        if (!corsProperties.getExposedHeaders().isEmpty()) {
            registration.exposedHeaders(corsProperties.getExposedHeaders().toArray(String[]::new));
        }
        log.info("跨域配置生效: path:{} origins:{} patterns:{} credentials:{}",
                corsProperties.getPathPattern(), origins, patterns, corsProperties.isAllowCredentials());
    }

}
