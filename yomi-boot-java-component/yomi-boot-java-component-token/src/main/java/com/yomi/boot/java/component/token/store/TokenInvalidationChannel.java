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
 * 再发一条通知；<b>扇出由 Redis 完成</b>——没有消费组、没有 ACK/PEL、没有历史与裁剪，也没有组名与停机清理。
 * <p>
 * 投递语义是"至多一次"：订阅连接断开期间的通知会丢，靠本地条目 TTL 兜底（且 {@code validate} 命中后
 * 仍会重校验有效期）——本场景需要的正是这个语义。发布失败只记 WARN，不影响撤销本身。
 * 顺序铁律：<b>先删 Redis，后发通知</b>——反过来会把"读到旧值 → 撤销 → 旧值才写进缓存"的竞态窗口
 * 从瞬间放大到每次撤销
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

    /** 是否启用：进程内缓存关闭时整层空转 */
    public boolean isEnabled() {
        return sessionCache.isEnabled();
    }

    /** 广播一条失效（一个令牌散列）；失败只记日志——撤销本身已生效，其它实例靠条目 TTL 兜底 */
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
            // pub/sub 没有 PEL：异常吞掉即可，条目 TTL 兜底
            log.warn("[token] 失效通知处理失败（已忽略）: channel={}", channel, e);
        }
    }
}
