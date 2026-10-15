package com.yomi.boot.java.core.boot.cors;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域配置，对应 yml 前缀 yomi.cors
 * <p>
 * 由 {@code CoreAutoConfiguration} 的 {@code @EnableConfigurationProperties} 注册（非组件扫描）
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.cors")
public class CorsProperties {

    /** 是否启用跨域支持，默认关闭（关闭时浏览器执行同源策略） */
    private boolean enabled = false;

    /** 跨域拦截的路径 */
    private String pathPattern = "/**";

    /** 允许的来源，如 https://www.example.com */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 允许的来源模式（支持 * 通配符），如 https://*.example.com；携带凭证时来源需配置在此项 */
    private List<String> allowedOriginPatterns = new ArrayList<>();

    /** 允许的请求方式 */
    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /** 允许的请求头 */
    private List<String> allowedHeaders = List.of("*");

    /** 允许暴露给前端的响应头 */
    private List<String> exposedHeaders = new ArrayList<>();

    /** 是否允许携带凭证（Cookie 等） */
    private boolean allowCredentials = false;

    /** 预检请求的缓存时间（秒） */
    private long maxAge = 1800;

}
