package com.yomi.boot.java.component.token.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 令牌会话快照
 * <p>
 * 令牌只是快照的键，会话快照是有效性的事实源：
 * <ul>
 *     <li>签发时 {@link #expireAt} 由服务按配置补齐，并决定 Redis 存储时长；</li>
 *     <li>校验时以 {@link #expireAt} 判定过期，Redis TTL 仅作为清理手段；</li>
 *     <li>撤销即删除快照，令牌随之立即失效。</li>
 * </ul>
 * 会话仅表达登录身份（如 {@link #userId}），不包含 RBAC 角色/权限；授权由 rbac 组件按 userId 独立求值。
 * 多租户等业务维度由接入方写入 {@link #context}，键名与类型由业务自定。
 * {@link #context} 值为可 JSON 序列化对象（不支持 null）。
 *
 * @author zwei
 */
@Data
public class TokenSession {

    /** 当前登录用户标识，必填且大于 0 */
    private Long userId;

    /** 端类型 */
    private String clientType;

    /** 设备标识 */
    private String deviceId;

    /** 登录 IP */
    private String loginIp;

    /** 签发时间，必填 */
    private Instant issuedAt;

    /** 过期时间，签发时按配置补齐 */
    private Instant expireAt;

    /** 业务自定义上下文参数 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> context = new HashMap<>();

    /**
     * 判断会话快照是否已过期，过期时间缺失视为已过期
     */
    public boolean isExpired() {
        return expireAt == null || Instant.now().isAfter(expireAt);
    }

    /**
     * 判断会话快照关键字段是否合法（用户标识与签发时间）
     */
    public boolean isValid() {
        return userId != null && userId > 0 && issuedAt != null;
    }

}
