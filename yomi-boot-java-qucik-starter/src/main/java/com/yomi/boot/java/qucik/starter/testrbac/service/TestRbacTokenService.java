package com.yomi.boot.java.qucik.starter.testrbac.service;

import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.session.TokenSession;
import com.yomi.boot.java.qucik.starter.testrbac.dto.TestRbacTokenResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.rbac.enabled", havingValue = "true")
public class TestRbacTokenService {

    /** 签发演示用令牌 */
    private final ITokenService tokenService;

    /** 为指定用户 id 签发令牌 */
    public TestRbacTokenResult issueForUser(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须大于 0");
        }
        TokenSession session = new TokenSession();
        session.setUserId(userId);
        session.setIssuedAt(Instant.now());
        String token = tokenService.issue(session);
        return new TestRbacTokenResult(userId, token);
    }

}
