package com.yomi.boot.java.component.rbac.web;

import com.yomi.boot.java.component.rbac.config.RbacProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 RBAC 鉴权拦截器（由 {@link com.yomi.boot.java.component.rbac.config.RbacAutoConfiguration} 注册为 Bean）
 */
@Slf4j
@RequiredArgsConstructor
public class RbacWebMvcConfig implements WebMvcConfigurer {

    /** 令牌拦截器为 HIGHEST_PRECEDENCE + 1000 */
    private static final int INTERCEPTOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 1100;

    /** HTTP 鉴权 */
    private final RbacInterceptor rbacInterceptor;

    /** 跳过鉴权路径等 */
    private final RbacProperties properties;

    /** 注册 RBAC 拦截器与路径规则 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        var registration = registry.addInterceptor(rbacInterceptor)
                .addPathPatterns("/**")
                .order(INTERCEPTOR_ORDER);
        List<String> excludes = properties.getSkipAuthorizePaths();
        if (!CollectionUtils.isEmpty(excludes)) {
            registration.excludePathPatterns(excludes.toArray(String[]::new));
        }
        log.info("[RBAC] 鉴权拦截器已注册: order={}, skip={}", INTERCEPTOR_ORDER, excludes);
    }

}
