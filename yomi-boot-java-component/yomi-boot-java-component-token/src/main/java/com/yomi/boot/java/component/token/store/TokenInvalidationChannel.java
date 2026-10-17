package com.yomi.boot.java.component.token.store;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 令牌失效通知（Redis pub/sub）
 * <p>
 * 一条消息 = 一个令牌散列，接收侧只做"清掉该散列的进程内条目"。删除/替换在 Redis 生效后先清本实例条目、
 * 再发一条通知。扇出由 Redis 完成，不使用消费组、ACK/PEL、历史与裁剪。
 * <p>
 * 投递语义为"至多一次"：订阅连接断开期间的通知会丢失，此时由本地条目按 TTL 过期后回源 Redis；
 * {@code validate} 命中缓存后也会重校验有效期。发布失败只记 WARN，不影响撤销本身。
 * 顺序固定为先删 Redis、后发通知：反过来会让"读到旧值 → 撤销 → 旧值才写进缓存"的竞态窗口
 * 从瞬时放大到每次撤销
 *
 * @author zwei
 */
@Slf4j
public class TokenInvalidationChannel implements MessageListener {

    /** 发布与订阅共用的通道名 */
    private final String channel;

    private final StringRedisTemplate redisTemplate;

    private final TokenSessionCache sessionCache;

    public TokenInvalidationChannel(StringRedisTemplate redisTemplate, String channel, TokenSessionCache sessionCache) {
        this.redisTemplate = redisTemplate;
        this.channel = channel;
        this.sessionCache = sessionCache;
    }

    /** 通道名（装配订阅时使用） */
    public String getChannel() {
        return channel;
    }

    /** 是否启用（由进程内缓存的启用状态决定） */
    public boolean isEnabled() {
        return sessionCache.isEnabled();
    }

    /** 广播一条失效（一个令牌散列）；失败只记日志，撤销本身已生效，其它实例按条目 TTL 到期后失效 */
    public void publish(String tokenHash) {
        if (!isEnabled() || StringUtils.isBlank(tokenHash)) {
            return;
        }
        try {
            redisTemplate.convertAndSend(channel, tokenHash);
        } catch (RuntimeException e) {
            log.warn("[token] 失效通知发布失败（其它实例最迟在本地条目 TTL 内生效）: channel={}", channel, e);
        }
    }

    /** 收到其它实例的失效通知：清掉本实例对应条目 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String tokenHash = message == null || message.getBody() == null
                ? null
                : new String(message.getBody(), StandardCharsets.UTF_8);
        if (StringUtils.isBlank(tokenHash)) {
            log.warn("[token] 失效通知内容为空，已忽略: channel={}", channel);
            return;
        }
        try {
            sessionCache.invalidate(tokenHash.trim());
        } catch (Exception e) {
            // pub/sub 没有 PEL，异常忽略即可；条目按 TTL 自然过期
            log.warn("[token] 失效通知处理失败（已忽略）: channel={}", channel, e);
        }
    }
}
