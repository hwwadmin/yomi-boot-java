package com.yomi.boot.java.component.token.store;

import lombok.Getter;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 令牌相关存储键构造
 * <p>
 * 令牌明文不落库：快照键使用令牌的 SHA-256 散列值作为后缀；
 * 前缀在构造时规范化（去首尾空白与 {@code :}），装配期统一注入各使用方。
 * <p>
 * Redis Cluster：用户索引与快照键共享 hash tag {@code {prefix:u:userId}}，Lua 双键同槽；
 * 校验热路径另用 {@code prefix:rt:{sha256}} → userId 路由键（单键读）。
 *
 * @author zwei
 */
public class TokenKeys {

    /** 用户桶路径片段 */
    private static final String USER_BUCKET_INFIX = ":u:";
    /** 用户令牌索引 SET 后缀 */
    private static final String TOKENS_SUFFIX = ":tokens";
    /** 会话快照键后缀 */
    private static final String SESSION_SUFFIX = ":s:";
    /** token→userId 路由键片段 */
    private static final String ROUTE_INFIX = ":rt:";
    /** 单点登录签发锁后缀 */
    private static final String ISSUE_LOCK_SUFFIX = ":lock:issue";
    /** 失效通知通道后缀（pub/sub 的 channel 不是键，无需纳入键清理） */
    private static final String INVALIDATE_CHANNEL_SUFFIX = ":invalidate";

    /** 规范化后的存储前缀 */
    @Getter
    private final String prefix;

    public TokenKeys(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalStateException("令牌存储前缀不能为空");
        }
        if (normalized.indexOf('{') >= 0 || normalized.indexOf('}') >= 0) {
            throw new IllegalStateException("令牌存储前缀不能包含 Redis hash tag 字符 { 或 }");
        }
        this.prefix = normalized;
    }

    /**
     * 用户维 hash tag（索引、快照、签发锁共槽）
     */
    public String userHashTag(Long userId) {
        assertUserId(userId);
        return "{" + prefix + USER_BUCKET_INFIX + userId + "}";
    }

    /**
     * 用户令牌索引键：{@code {prefix:u:userId}:tokens}
     */
    public String userTokensKey(Long userId) {
        return userHashTag(userId) + TOKENS_SUFFIX;
    }

    /**
     * 快照键：{@code {prefix:u:userId}:s:{sha256(token)}}
     */
    public String sessionKey(Long userId, String token) {
        return sessionKeyByStorageToken(userId, hashToken(token));
    }

    /**
     * 由用户与已散列的存储标识拼快照键
     */
    public String sessionKeyByStorageToken(Long userId, String storageToken) {
        assertUserId(userId);
        return userHashTag(userId) + SESSION_SUFFIX + storageToken;
    }

    /**
     * 拼旧快照键时使用的固定前缀（Lua 按成员散列批量 DEL）
     */
    public String sessionKeyPrefix(Long userId) {
        return userHashTag(userId) + SESSION_SUFFIX;
    }

    /**
     * 校验路由键：{@code prefix:rt:{sha256(token)}} → userId 字符串
     */
    public String routeKey(String token) {
        return routeKeyByStorageToken(hashToken(token));
    }

    /**
     * 由散列拼校验路由键
     */
    public String routeKeyByStorageToken(String storageToken) {
        return prefix + ROUTE_INFIX + storageToken;
    }

    /**
     * 单点登录签发锁键：{@code {prefix:u:userId}:lock:issue}
     */
    public String issueLockKey(Long userId) {
        return userHashTag(userId) + ISSUE_LOCK_SUFFIX;
    }

    /**
     * 失效通知通道名：{@code prefix:invalidate}
     */
    public String invalidateChannel() {
        return prefix + INVALIDATE_CHANNEL_SUFFIX;
    }

    /**
     * 集成测试 / 运维清理用的 KEYS 模式（Standalone；Cluster 慎用 KEYS）
     */
    public List<String> cleanupKeyPatterns() {
        return List.of(prefix + ROUTE_INFIX + "*", "{" + prefix + USER_BUCKET_INFIX + "*");
    }

    /**
     * 令牌散列（SHA-256 十六进制）
     */
    public static String hashToken(String token) {
        return DigestUtils.sha256Hex(StringUtils.trimToEmpty(token));
    }

    /**
     * 规范化存储前缀：去首尾空白与 {@code :}
     */
    public static String normalizePrefix(String prefix) {
        return StringUtils.strip(StringUtils.trimToEmpty(prefix), ":");
    }

    /** 校验用户 ID 为正数 */
    private static void assertUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
    }

}
