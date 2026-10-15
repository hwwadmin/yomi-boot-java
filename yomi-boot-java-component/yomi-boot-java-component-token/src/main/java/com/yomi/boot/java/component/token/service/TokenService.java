package com.yomi.boot.java.component.token.service;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.config.TokenProperties;
import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.exception.TokenErrorCode;
import com.yomi.boot.java.component.token.generator.TokenGenerator;
import com.yomi.boot.java.component.token.session.TokenContext;
import com.yomi.boot.java.component.token.session.TokenSession;
import com.yomi.boot.java.component.token.store.TokenKeys;
import com.yomi.boot.java.component.token.store.TokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 令牌服务默认实现
 * <p>
 * 单点登录签发复用 redis 组件的 {@link LockUtils}（等锁超时转换为 TOKEN_ISSUE_BUSY），不自行实现锁机制。
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class TokenService implements ITokenService {

    /** 令牌字符串生成 */
    private final TokenGenerator generator;

    /** 会话快照存储 */
    /** 会话快照存储 */
    private final TokenStore store;
    /** 令牌 TTL、多登等配置 */
    private final TokenProperties properties;

    /** Redis 键名 */
    private final TokenKeys keys;

    /** 为用户签发令牌并写入存储 */
    @Override
    public String issue(TokenSession session) {
        TokenSession target = session == null ? new TokenSession() : session;
        Instant now = Instant.now();
        if (target.getIssuedAt() == null) {
            target.setIssuedAt(now);
        }
        if (target.getExpireAt() == null) {
            target.setExpireAt(target.getIssuedAt().plus(properties.getTtl()));
        }
        if (!target.isValid()) {
            throw BizException.exception(TokenErrorCode.SESSION_INVALID.getCode(), "令牌会话不合法：用户ID与签发时间不能为空");
        }
        if (!target.getExpireAt().isAfter(now)) {
            throw BizException.exception(TokenErrorCode.SESSION_INVALID.getCode(), "令牌会话不合法：过期时间必须晚于当前时间");
        }
        if (!target.getExpireAt().isAfter(target.getIssuedAt())) {
            throw BizException.exception(TokenErrorCode.SESSION_INVALID.getCode(), "令牌会话不合法：过期时间必须晚于签发时间");
        }

        if (properties.isAllowMultiLogin()) {
            return issueToken(target);
        }
        try {
            return LockUtils.tryLockWithLease(keys.issueLockKey(target.getUserId()),
                    properties.getLockWait().toMillis(), properties.getLockLease().toMillis(),
                    TimeUnit.MILLISECONDS, () -> issueToken(target), "令牌签发失败");
        } catch (LockBusyException e) {
            throw BizException.exception(TokenErrorCode.ISSUE_BUSY.getCode(), TokenErrorCode.ISSUE_BUSY.getMessage());
        }
    }

    /** 校验令牌有效并可选续期 */
    @Override
    public TokenSession validate(String token) {
        if (token == null || token.isBlank()) {
            throw TokenAuthException.of(TokenErrorCode.INVALID);
        }
        TokenSession session = store.load(token);
        if (session == null || !session.isValid()) {
            throw TokenAuthException.of(TokenErrorCode.INVALID);
        }
        if (session.isExpired()) {
            deleteExpiredQuietly(token);
            throw TokenAuthException.of(TokenErrorCode.INVALID);
        }
        if (properties.isRenewOnValidate()) {
            renewSessionQuietly(token, session);
        }
        return session;
    }

    /** 吊销单个令牌 */
    @Override
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            throw TokenAuthException.of(TokenErrorCode.INVALID);
        }
        store.delete(token);
    }

    /** 吊销用户下全部令牌 */
    @Override
    public void revokeByUserId(Long userId) {
        assertUserId(userId);
        store.deleteByUserId(userId);
    }

    /** 列出用户当前有效令牌 */
    @Override
    public List<TokenSession> listByUserId(Long userId) {
        assertUserId(userId);
        List<TokenSession> sessions = new ArrayList<>(store.listByUserId(userId));
        sessions.sort(Comparator.comparing(TokenSession::getIssuedAt).reversed());
        return sessions;
    }

    /** 从令牌解析用户 id */
    @Override
    public Long getUserId() {
        return TokenContext.operatorId();
    }

    /**
     * 生成新令牌并保存快照；单点登录场景须由调用方持有用户维度锁
     */
    private String issueToken(TokenSession session) {
        String token = generator.newToken();
        if (properties.isAllowMultiLogin()) {
            store.save(token, session);
        } else {
            store.replaceAllForUser(token, session);
        }
        return token;
    }

    /**
     * 过期快照清理失败不遮蔽"令牌无效"的判定结果
     */
    private void deleteExpiredQuietly(String token) {
        try {
            store.delete(token);
        } catch (Exception e) {
            log.warn("清理过期令牌快照失败: {}", e.getMessage());
        }
    }

    /**
     * 滑动续期：续期失败不遮蔽本次校验成功结果。
     * <p>续期不超过 {@code issuedAt + max-lifetime}（默认 1 天）；已达上限则跳过写回。
     * 写回使用 {@link TokenStore#renewIfPresent}，会话已失效时不更新存储与内存态</p>
     */
    private void renewSessionQuietly(String token, TokenSession session) {
        Instant previousExpireAt = session.getExpireAt();
        Instant newExpireAt = Instant.now().plus(properties.getTtl());
        Duration maxLifetime = properties.getMaxLifetime();
        if (maxLifetime != null && !maxLifetime.isZero()) {
            Instant cap = session.getIssuedAt().plus(maxLifetime);
            if (newExpireAt.isAfter(cap)) {
                newExpireAt = cap;
            }
        }
        if (!newExpireAt.isAfter(previousExpireAt)) {
            // 已到绝对上限（或本次续期不能延长）：不写回、不改内存态
            return;
        }
        session.setExpireAt(newExpireAt);
        try {
            if (!store.renewIfPresent(token, session)) {
                session.setExpireAt(previousExpireAt);
                log.warn("令牌滑动续期未生效：会话已不存在（可能刚被注销或踢出）");
            }
        } catch (Exception e) {
            session.setExpireAt(previousExpireAt);
            log.warn("令牌滑动续期失败: {}", e.getMessage());
        }
    }

    /** 校验用户 ID 合法 */
    private static void assertUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw BizException.exception(TokenErrorCode.INVALID_INPUT.getCode(), "用户ID不能为空");
        }
    }

}
