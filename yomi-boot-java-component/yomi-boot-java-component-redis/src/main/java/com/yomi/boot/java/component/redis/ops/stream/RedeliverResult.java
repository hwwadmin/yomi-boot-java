package com.yomi.boot.java.component.redis.ops.stream;

/**
 * {@link StreamRedisSupport#claimAndRedeliver} 一轮补投的结果计数
 * <p>
 * 四种去向分开计数，调用方据此区分"认领了几条"与"成功/失败/跳过各几条"
 *
 * @param claimed 本轮认领（XCLAIM）并尝试处理的条数，等于 acked + failed
 * @param acked   处理成功且已 ACK（XACK）的条数
 * @param failed  处理失败或 ACK 失败的条数（不 ACK 留 PEL）
 * @param skipped 累计投递次数达到阈值、本轮跳过重投的毒消息条数（不 ACK 不删，仍在 PEL，需人工处理）
 */
public record RedeliverResult(int claimed, int acked, int failed, int skipped) {
}
