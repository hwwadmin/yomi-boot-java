package com.yomi.boot.java.component.rbac.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `skip-authorize-paths` 装配期自检：首尾空格型笔误经 `excludePathPatterns` 既不报错也永不命中
 * （RbacWebMvcConfig 照常注册，放行路径未生效、全部请求返回 403），只能在启动期拦下；口径与 token 的
 * `skip-validate-paths` 一致
 */
class RbacPropertiesTest {

    /** 默认即 scheduled-refresh-enabled=false：校验照常执行，不受"定时刷新关闭"影响 */
    @Test
    void whitespaceSkipAuthorizePathRejected() {
        RbacProperties properties = enabled();
        properties.setSkipAuthorizePaths(List.of("/test/ds/**", " /api/public "));

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(error.getMessage().contains("skip-authorize-paths"), error.getMessage());
        assertTrue(error.getMessage().contains("/api/public"), error.getMessage());
    }

    @Test
    void nullSkipAuthorizePathRejected() {
        RbacProperties properties = enabled();
        properties.setSkipAuthorizePaths(Arrays.asList("/api/public", null));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void normalSkipAuthorizePathsPass() {
        RbacProperties properties = enabled();
        properties.setSkipAuthorizePaths(List.of("/api/public/**", "/actuator/health"));

        assertDoesNotThrow(properties::validate);
    }

    /** 未启用时不校验：与 token 一致，没开组件时配置问题不会拦停启动 */
    @Test
    void disabledSkipsValidation() {
        RbacProperties properties = new RbacProperties();
        properties.setSkipAuthorizePaths(List.of(" /api/public "));

        assertDoesNotThrow(properties::validate);
    }

    private static RbacProperties enabled() {
        RbacProperties properties = new RbacProperties();
        properties.setEnabled(true);
        return properties;
    }

}
