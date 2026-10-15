package com.yomi.boot.java.qucik.starter.testrbac.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.qucik.starter.testrbac.dto.TestRbacFlowResult;
import com.yomi.boot.java.qucik.starter.testrbac.dto.TestRbacTokenResult;
import com.yomi.boot.java.qucik.starter.testrbac.service.TestRbacFlowService;
import com.yomi.boot.java.qucik.starter.testrbac.service.TestRbacTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RBAC 组件流程测试（服务层准备数据 + 签发 token）。
 * <p>
 * 前置：{@code yomi.rbac.enabled=true}、Redis、已执行
 * {@code yomi-boot-java-component-rbac/sql/sys_rbac.sql}。
 * HTTP 拦截验证见 {@link TestRbacProtectedController} 与
 * {@code TestRbacFlowApiTest}。
 * <ul>
 *     <li>GET /test/ds/test-rbac/flow</li>
 *     <li>GET /test/ds/test-rbac/issue-token?userId=</li>
 * </ul>
 */
@RestController
@RequestMapping("/test/ds/test-rbac")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.rbac.enabled", havingValue = "true")
public class TestRbacFlowController {

    /** RBAC 演示流程 */
    private final TestRbacFlowService flowService;

    /** 演示用令牌签发 */
    private final TestRbacTokenService tokenService;

    /** 登录态令牌（演示签发） */
    private final ITokenService loginITokenService;

    @GetMapping("/flow")
    public Response<TestRbacFlowResult> flow() {
        return Response.success(flowService.runFlow());
    }

    /** 为 HTTP 探测签发令牌（路径在 token/rbac 放行列表内） */
    @GetMapping("/issue-token")
    public Response<TestRbacTokenResult> issueToken(@RequestParam("userId") long userId) {
        return Response.success(tokenService.issueForUser(userId));
    }

    /** 校验 Authorization 头中的令牌并返回 userId（路径在放行列表内） */
    @GetMapping("/whoami")
    public Response<Long> whoami(@RequestParam("token") String token) {
        return Response.success(loginITokenService.validate(token).getUserId());
    }

}
