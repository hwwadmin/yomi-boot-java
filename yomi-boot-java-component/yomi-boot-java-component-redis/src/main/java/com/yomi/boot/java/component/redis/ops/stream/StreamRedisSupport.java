package com.yomi.boot.java.component.redis.ops.stream;

import com.yomi.boot.java.base.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.*;

/**
 * Redis Stream 发布与消费组订阅
 * <p>
 * 消费语义为至少一次（handler 须幂等）：消息经 XREADGROUP 进入消费组 PEL；
 * 处理成功由本类 ACK（XACK），处理失败不 ACK，消息留在 PEL，可由 {@link #claimAndRedeliver} 重投。
 * </p>
 *
 * @author zwei
 */
@Slf4j
public class StreamRedisSupport {

    /** 默认单字段消息体字段名 */
    public static final String PAYLOAD_FIELD = "payload";

    /** 补投单次认领的消息条数上限 */
    public static final int DEFAULT_CLAIM_BATCH = 100;

    /**
     * 毒消息阈值：单条消息累计投递次数达到该值仍未 ACK，则跳过重投并告警
     * （不 ACK 不删，仍在 PEL，需人工处理）
     */
    public static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 10;

    /** Stream 读写 */
    private final StringRedisTemplate redisTemplate;

    /** Spring Data Stream 监听容器 */
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer;

    public StreamRedisSupport(StringRedisTemplate redisTemplate,
                              StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.listenerContainer = Objects.requireNonNull(listenerContainer, "listenerContainer");
    }

    /**
     * 向 Stream 追加一条多字段消息
     *
     * @return Redis 消息 ID
     */
    public String add(String streamKey, Map<String, String> fields) {
        requireStreamKey(streamKey);
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Stream 消息 fields 不能为空");
        }
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(streamKey)
                .ofStrings(fields);
        RecordId id = redisTemplate.opsForStream().add(record);
        return id.getValue();
    }

    /**
     * 向 Stream 追加单字段 {@link #PAYLOAD_FIELD} 消息
     */
    public String add(String streamKey, String payload) {
        requireStreamKey(streamKey);
        if (payload == null) {
            throw new IllegalArgumentException("Stream 消息 payload 不能为 null");
        }
        return add(streamKey, Map.of(PAYLOAD_FIELD, payload));
    }

    /**
     * 将对象序列化为 JSON 后写入 {@link #PAYLOAD_FIELD}
     */
    public String addJson(String streamKey, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Stream 消息 value 不能为 null");
        }
        return add(streamKey, JsonUtils.toJson(value));
    }

    /**
     * 追加消息并近似裁剪 Stream 长度（{@code MAXLEN ~}）
     */
    public String add(String streamKey, Map<String, String> fields, long maxLen) {
        requireStreamKey(streamKey);
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Stream 消息 fields 不能为空");
        }
        if (maxLen <= 0) {
            throw new IllegalArgumentException("maxLen 必须大于 0");
        }
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(streamKey)
                .ofStrings(fields);
        RedisStreamCommands.XAddOptions options = RedisStreamCommands.XAddOptions.maxlen(maxLen).approximateTrimming(true);
        RecordId id = redisTemplate.opsForStream().add(record, options);
        return id.getValue();
    }

    /**
     * 注册消费组监听：建组（仅新消息 {@link ReadOffset#latest()}）+ 成功自动 ACK + 失败保留 PEL。
     * <p>
     * 消费语义为至少一次（handler 必须幂等）：
     * <ul>
     *     <li>handler 正常返回 → 立即 XACK，消息离开 PEL；</li>
     *     <li>handler 抛异常 → 不 ACK，消息留在 PEL 等待 {@link #claimAndRedeliver} 重投
     *     （进程崩溃同理），异常不会取消订阅；</li>
     *     <li>ACK 本身失败（Redis 抖动）→ 消息留在 PEL，下轮会重复投递。</li>
     * </ul>
     * 同组多实例消费时每个实例必须使用不同的 {@code consumerName}。
     *
     * @param streamKey    Stream Redis key
     * @param group        消费组名
     * @param consumerName 组内消费者名，多实例需唯一
     * @param handler      消息处理逻辑
     * @return 订阅句柄，可用于 {@link #cancel(Subscription)}
     */
    public Subscription listen(String streamKey, String group, String consumerName, StreamMessageHandler handler) {
        requireStreamKey(streamKey);
        requireText(group, "group");
        requireText(consumerName, "consumerName");
        Objects.requireNonNull(handler, "handler");

        ensureGroup(streamKey, group);

        Consumer consumer = Consumer.from(group, consumerName);
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());
        // autoAcknowledge=false：消息进入 PEL，由本类在成功后 ACK
        // cancelOnError=false：读取异常只记日志，不取消订阅
        StreamMessageListenerContainer.StreamReadRequest<String> request =
                StreamMessageListenerContainer.StreamReadRequest.builder(offset)
                        .consumer(consumer)
                        .autoAcknowledge(false)
                        .cancelOnError(throwable -> false)
                        .build();
        return listenerContainer.register(request, message ->
                processWithAck(message.getStream(), group, message.getId().getValue(), message.getValue(), handler));
    }

    /**
     * 认领 PEL 中超过 {@code minIdle} 未确认的消息并重新投递（补投入口，供调用方定时触发，如 roundrobin 任务）。
     * <p>处理成功自动 ACK；失败不 ACK（留 PEL 等下一轮）。单轮只扫描 PEL 头部
     * {@link #DEFAULT_CLAIM_BATCH} 条，累计投递次数达到 {@link #DEFAULT_MAX_DELIVERY_ATTEMPTS}
     * 的毒消息跳过重投并告警（见 {@link RedeliverResult}）</p>
     */
    public RedeliverResult claimAndRedeliver(String streamKey, String group, String consumerName, Duration minIdle,
                                             StreamMessageHandler handler) {
        return claimAndRedeliver(streamKey, group, consumerName, minIdle, DEFAULT_CLAIM_BATCH,
                DEFAULT_MAX_DELIVERY_ATTEMPTS, handler);
    }

    /**
     * 同 {@link #claimAndRedeliver(String, String, String, Duration, StreamMessageHandler)}，并限制单次认领条数
     */
    public RedeliverResult claimAndRedeliver(String streamKey, String group, String consumerName, Duration minIdle,
                                             int maxCount, StreamMessageHandler handler) {
        return claimAndRedeliver(streamKey, group, consumerName, minIdle, maxCount,
                DEFAULT_MAX_DELIVERY_ATTEMPTS, handler);
    }

    /**
     * 同 {@link #claimAndRedeliver(String, String, String, Duration, int, StreamMessageHandler)}，
     * 并可指定毒消息阈值。
     * <p>
     * 认领范围是 PEL 头部前 {@code maxCount} 条（XPENDING 按消息 ID 升序）：累计投递次数已达
     * {@code maxDeliveryAttempts} 的消息本轮跳过并打 WARN（传 {@link Integer#MAX_VALUE} 表示一直重投）；
     * 头部堆积的毒消息超过 {@code maxCount} 时后面的积压本轮轮不到，需人工处理
     * （XPENDING 查看，XCLAIM 重投或 XACK 丢弃）</p>
     *
     * @param maxDeliveryAttempts 毒消息阈值（含本轮之前已在 PEL 的投递次数），必须大于 0
     */
    public RedeliverResult claimAndRedeliver(String streamKey, String group, String consumerName, Duration minIdle,
                                             int maxCount, int maxDeliveryAttempts, StreamMessageHandler handler) {
        requireStreamKey(streamKey);
        requireText(group, "group");
        requireText(consumerName, "consumerName");
        Objects.requireNonNull(minIdle, "minIdle");
        Objects.requireNonNull(handler, "handler");
        if (maxCount <= 0) {
            throw new IllegalArgumentException("maxCount 必须大于 0");
        }
        if (maxDeliveryAttempts <= 0) {
            throw new IllegalArgumentException("maxDeliveryAttempts 必须大于 0");
        }

        PendingMessages pending = redisTemplate.opsForStream()
                .pending(streamKey, group, Range.unbounded(), maxCount);
        if (pending == null || pending.isEmpty()) {
            return new RedeliverResult(0, 0, 0, 0);
        }
        List<RecordId> stale = new ArrayList<>();
        int skipped = 0;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            if (message.getTotalDeliveryCount() >= maxDeliveryAttempts) {
                skipped++;
                log.warn("[redis] Stream 消息累计投递 {} 次仍未 ACK，跳过重投（视为毒消息；消息仍在 PEL，"
                                + "可用 XPENDING 查看、XCLAIM 人工重投或 XACK 丢弃）: stream={}, group={}, id={}",
                        message.getTotalDeliveryCount(), streamKey, group, message.getId().getValue());
                continue;
            }
            stale.add(message.getId());
        }
        if (stale.isEmpty()) {
            return new RedeliverResult(0, 0, 0, skipped);
        }

        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                .claim(streamKey, group, consumerName, minIdle, stale.toArray(new RecordId[0]));
        if (claimed == null || claimed.isEmpty()) {
            return new RedeliverResult(0, 0, 0, skipped);
        }
        int acked = 0;
        int failed = 0;
        for (MapRecord<String, Object, Object> record : claimed) {
            if (processWithAck(record.getStream(), group, record.getId().getValue(), toStringBody(record.getValue()),
                    handler)) {
                acked++;
            } else {
                failed++;
            }
        }
        return new RedeliverResult(acked + failed, acked, failed, skipped);
    }

    /** Stream 字段值在 Redis 中恒为字符串，转回 String 视图 */
    private static Map<String, String> toStringBody(Map<Object, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> body = new LinkedHashMap<>(raw.size());
        raw.forEach((key, value) -> body.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
        return body;
    }

    /**
     * 取消订阅
     */
    public void cancel(Subscription subscription) {
        if (subscription != null) {
            listenerContainer.remove(subscription);
        }
    }

    /**
     * 处理单条消息并决定 ACK：正常返回立即 XACK；异常只记 ERROR、不 ACK（留 PEL 等重投）。
     * <p>业务异常在本方法内捕获并记录日志，不向上抛出，避免取消 Stream 订阅。
     * {@link Error} 仍向上抛出，便于发现致命故障</p>
     *
     * @return true=已 ACK
     */
    boolean processWithAck(String streamKey, String group, String recordId, Map<String, String> body,
                           StreamMessageHandler handler) {
        try {
            handler.onMessage(streamKey, recordId, body);
        } catch (Exception e) {
            log.error("[redis] Stream 消息处理失败，不 ACK（保留在 PEL 等待重投）: stream={}, group={}, id={}",
                    streamKey, group, recordId, e);
            return false;
        }
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, group, RecordId.of(recordId));
            return true;
        } catch (Exception e) {
            log.error("[redis] Stream 消息 ACK 失败，后续可能重复投递（handler 需幂等）: stream={}, group={}, id={}",
                    streamKey, group, recordId, e);
            return false;
        }
    }

    /** 确保演示数据：Group */
    private void ensureGroup(String streamKey, String group) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
        } catch (InvalidDataAccessApiUsageException ex) {
            if (!isBusyGroup(ex)) {
                throw ex;
            }
        } catch (RuntimeException ex) {
            if (!isBusyGroup(ex)) {
                throw ex;
            }
        }
    }

    /** 判断是否为消费组已存在异常 */
    private static boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 校验 Stream 键非空 */
    private static void requireStreamKey(String streamKey) {
        requireText(streamKey, "streamKey");
    }

    /** 校验字符串参数非空白 */
    private static void requireText(String value, String name) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

}
