package com.yomi.boot.java.component.token;

import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.session.TokenSession;

import java.util.List;

/**
 * 令牌服务（服务层门面）
 * <p>
 * 令牌为不透明键，会话快照存于存储层：
 * <ul>
 *     <li>签发：补齐会话默认字段；单点登录（{@code yomi.token.allow-multi-login=false}）时在用户维度分布式锁内
 *     "删旧 + 建新"，锁复用 redis 组件的 {@code LockUtils}，等锁超时返回 TOKEN_ISSUE_BUSY；</li>
 *     <li>校验：命中快照且未过期才有效，过期顺手清理；可选 {@code yomi.token.renew-on-validate=true} 滑动续期；</li>
 *     <li>撤销：删除快照即失效。</li>
 * </ul>
 *
 * @author zwei
 */
public interface ITokenService {

    /**
     * 签发新的访问令牌，会话快照缺失字段按配置补齐（签发时间、过期时间）
     */
    String issue(TokenSession session);

    /**
     * 校验令牌并返回会话快照
     * 令牌缺失、无效或过期时抛 {@link TokenAuthException}（TOKEN_INVALID）
     */
    TokenSession validate(String token);

    /**
     * 使指定令牌立即失效
     */
    void revoke(String token);

    /**
     * 使指定用户的全部令牌立即失效
     */
    void revokeByUserId(Long userId);

    /**
     * 读取指定用户全部有效会话，按签发时间倒序
     */
    List<TokenSession> listByUserId(Long userId);

    /**
     * 读取当前请求上下文中的登录用户 ID（鉴权拦截器写入的会话快照），无会话时返回 null
     * <p>
     * 仅在 Web 请求线程内有效，{@code @Async} 或自建线程不会自动携带会话。
     */
    Long getUserId();

}
