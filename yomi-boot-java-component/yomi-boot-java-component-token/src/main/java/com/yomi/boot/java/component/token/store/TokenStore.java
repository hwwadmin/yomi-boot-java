package com.yomi.boot.java.component.token.store;

import com.yomi.boot.java.component.token.session.TokenSession;

import java.util.List;

/**
 * 令牌会话存储（存储层门面）
 * <p>
 * 存储时长由 {@link TokenSession#getExpireAt()} 推导，调用方无需额外传 TTL；
 * 默认实现为 {@link RedisTokenStore}，可按需替换（Bean 声明处 {@code @ConditionalOnMissingBean} 可覆盖）。
 *
 * @author zwei
 */
public interface TokenStore {

    /**
     * 保存令牌与会话快照，会话必须合法且未过期
     */
    void save(String token, TokenSession session);

    /**
     * 读取令牌对应的会话快照，未命中返回 null
     */
    TokenSession load(String token);

    /**
     * 删除令牌会话快照，并从用户索引移除对应成员（快照已缺失时仍删除快照键）
     */
    void delete(String token);

    /**
     * 删除指定用户的全部令牌会话
     */
    void deleteByUserId(Long userId);

    /**
     * 单点登录签发：原子地清除该用户全部旧令牌并保存新令牌快照
     */
    void replaceAllForUser(String token, TokenSession session);

    /**
     * 滑动续期：仅当会话仍存在时才写回快照（原子守卫，防止把刚被注销/踢出的会话写回复活）。
     *
     * @return true=已写回；false=会话已不存在，未做任何写入（调用方不应视为续期成功）
     */
    boolean renewIfPresent(String token, TokenSession session);

    /**
     * 读取指定用户的全部有效会话，陈旧的索引成员会被清理
     */
    List<TokenSession> listByUserId(Long userId);

}
