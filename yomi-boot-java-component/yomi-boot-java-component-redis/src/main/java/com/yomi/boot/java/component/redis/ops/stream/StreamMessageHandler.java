package com.yomi.boot.java.component.redis.ops.stream;

import java.util.Map;

/**
 * Redis Stream 消息处理回调
 * <p>
 * 消费语义为至少一次（handler 必须幂等）：
 * <ul>
 *     <li>正常返回表示处理成功 → 组件立即 ACK（XACK），消息离开 PEL；</li>
 *     <li>抛出 {@link Exception} → 组件记 ERROR 后不 ACK，消息保留在 PEL，
 *     用 {@link StreamRedisSupport#claimAndRedeliver} 重投（进程崩溃同理）；</li>
 *     <li>ACK 本身失败（Redis 抖动）也会导致后续重复投递。</li>
 * </ul>
 *
 * @author zwei
 */
@FunctionalInterface
public interface StreamMessageHandler {

    /**
     * @param streamKey Stream 的 Redis key
     * @param messageId  消息 ID
     * @param body       字段 Map（均为字符串）
     */
    void onMessage(String streamKey, String messageId, Map<String, String> body);

}
