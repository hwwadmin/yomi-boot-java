package com.yomi.boot.java.component.token.web;

import com.yomi.boot.java.component.token.config.TokenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
import java.util.List;

/**
 * 令牌拦截器注册
 * <p>
 * 默认作用于全部路径；不参与校验的路径仅由 {@code yomi.token.skip-validate-paths} 配置。
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class TokenWebMvcConfig implements WebMvcConfigurer {

    /** 拦截器顺序：早于业务默认(0)序拦截器，使业务拦截器在 preHandle 阶段即可读取令牌会话 */
    private static final int INTERCEPTOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    /** 令牌鉴权拦截器 */
    private final TokenInterceptor tokenInterceptor;

    /** 跳过校验路径等 */
    private final TokenProperties properties;

    /** 注册令牌解析拦截器 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        var registration = registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/**")
                .order(INTERCEPTOR_ORDER);
        List<String> excludes = resolveExcludePatterns(properties);
        if (!CollectionUtils.isEmpty(excludes)) {
            registration.excludePathPatterns(excludes);
        }
        log.info("[Token] 令牌拦截器已注册: order={}, skip={}", INTERCEPTOR_ORDER, excludes);
    }

    /** 解析跳过令牌校验的路径列表 */
    private static List<String> resolveExcludePatterns(TokenProperties properties) {
        if (properties == null || CollectionUtils.isEmpty(properties.getSkipValidatePaths())) {
            return Collections.emptyList();
        }
        return properties.getSkipValidatePaths();
    }

}
