package com.yomi.boot.java.qucik.starter.testtoken.service;

import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.session.TokenSession;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Token 组件流程（签发 / 单点踢旧 / 撤销），与 RBAC 演示解耦。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.token.enabled", havingValue = "true")
public class TestTokenFlowService {

    /** 演示用户，避免与 RBAC flow 用户冲突 */
    public static final long DEMO_USER_ID = 90_001L;

    /** 令牌组件服务 */
    private final ITokenService tokenService;

    /** 签发、校验、吊销演示流程 */
    public List<String> runFlow() {
        List<String> steps = new ArrayList<>();
        String tokenA = issue(DEMO_USER_ID);
        TokenSession sessionA = tokenService.validate(tokenA);
        Assert.isTrue(sessionA.getUserId() == DEMO_USER_ID, "tokenA userId 不符");
        steps.add("1.首次签发: userId=" + DEMO_USER_ID + ", tokenLen=" + tokenA.length());

        String tokenB = issue(DEMO_USER_ID);
        tokenService.validate(tokenB);
        assertTokenInvalid(tokenA, "2.单点登录踢旧");
        steps.add("2.单点登录: 新 token 有效, 旧 token 已失效");

        tokenService.revoke(tokenB);
        assertTokenInvalid(tokenB, "3.撤销后");
        steps.add("3.撤销: tokenB 已失效");

        steps.add("4.流程通过");
        return steps;
    }

    /** 为演示用户签发令牌 */
    private String issue(long userId) {
        TokenSession session = new TokenSession();
        session.setUserId(userId);
        session.setIssuedAt(Instant.now());
        return tokenService.issue(session);
    }

    /** 断言令牌已失效 */
    private void assertTokenInvalid(String token, String step) {
        try {
            tokenService.validate(token);
            throw new IllegalStateException(step + " 期望令牌无效，但校验通过");
        } catch (TokenAuthException ignored) {
            // expected
        }
    }

}
