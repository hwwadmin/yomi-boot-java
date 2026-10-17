package com.yomi.boot.java.component.httplog.queue.redis;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.AbstractBufferedTransport;
import com.yomi.boot.java.component.httplog.queue.HttpLogBatchHandler;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * redis 模式传输：L1 有界缓冲 → 发送线程批量写入 Redis → 消费线程批量读取 → 回调
 * <p>structure=list（默认）：RPUSH 批量写 / LPOP count 批量读（Redis ≥ 6.2），pop 即删不重复；
 * 写入后超过 {@code yomi.httplog.redis.max-length} 上限时丢队首最旧日志（0=不限）；
 * structure=stream：XADD + 消费组 XREADGROUP；无论成败都按批 XACK（失败按丢弃计数）；
 * 崩溃遗留的 PEL 由同名 consumer（默认主机名，可配）重启后补投，每个 consumer 只处理自己的 PEL。
 * <p>发送前完成 body 补脱敏：进入介质的即为脱敏后数据。
 *
 * @author zwei
 */
@Slf4j
public class RedisHttpLogTransport extends AbstractBufferedTransport implements HttpLogSink, HttpLogConsumer {

    /** Redis Stream 消息体字段名 */
    private static final String PAYLOAD_FIELD = "payload";

    /** Redis 读写 */
    private final StringRedisTemplate redisTemplate;

    /** 队列键：{prefix}:{serviceName} */
    private final String queueKey;

    /** 消费组（stream 结构） */
    private final String group;

    /** 消费者名（多实例必须互异） */
    private final String consumerName;

    /** 缓冲 → Redis 的发送线程 */
    private volatile Thread sender;

    /** List/Stream 消费线程 */
    private volatile Thread consumerThread;

    /** 建组失败是否已告警：连续失败期间仅 WARN 一次（仅消费线程访问），成功或恢复后重置 */
    private boolean groupWarned;

    public RedisHttpLogTransport(HttpLogProperties properties, HttpLogKeys keys,
                                 StringRedisTemplate redisTemplate, HttpLogDesensitizer desensitizer,
                                 HttpLogMetrics metrics) {
        super("redis", properties, metrics, desensitizer);
        this.redisTemplate = redisTemplate;
        this.queueKey = keys.redisQueueKey(properties);
        this.group = keys.redisGroup(properties);
        this.consumerName = resolveConsumerName(properties.getRedis());
    }

    /** 启动发送线程与消费线程 */
    @Override
    public void start(HttpLogBatchHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sender = startLoopThread("httplog-redis-sender", "redis-send", "send-failed", "redis-send-loop");
        boolean stream = properties.getRedis().getStructure() == HttpLogProperties.RedisStructure.STREAM;
        consumerThread = Thread.ofPlatform().name("httplog-redis-consumer").daemon(true).start(() -> {
            if (stream) {
                consumeStream(handler);
            } else {
                consumeList(handler);
            }
        });
        log.info("[httplog]redis 模式已启动: structure={}, key={}, group={}, consumer={}",
                properties.getRedis().getStructure(), queueKey, group, consumerName);
    }

    /** 停止消费线程 */
    @Override
    public void stop(Duration timeout) {
        flush(timeout);
        running.set(false);
        stopThread(sender, timeout);
        stopThread(consumerThread, timeout);
    }

    /** 批量写入介质：stream 用 XADD，list 用 RPUSH 批量写 */
    @Override
    protected void writeBatch(List<HttpLogRecord> batch) {
        if (properties.getRedis().getStructure() == HttpLogProperties.RedisStructure.STREAM) {
            for (HttpLogRecord record : batch) {
                redisTemplate.opsForStream().add(StreamRecords.newRecord()
                        .in(queueKey)
                        .ofStrings(Map.of(PAYLOAD_FIELD, JsonUtils.toJson(record))));
            }
            return;
        }
        List<String> payloads = new ArrayList<>(batch.size());
        for (HttpLogRecord record : batch) {
            payloads.add(JsonUtils.toJson(record));
        }
        // RPUSH key v1..vN：单命令批量写入，返回值即写入后的队列长度
        Long size = redisTemplate.opsForList().rightPushAll(queueKey, payloads);
        int maxLength = properties.getRedis().getMaxLength();
        if (maxLength > 0 && size != null && size > maxLength) {
            // 超上限：保留最新，丢队首最旧
            redisTemplate.opsForList().trim(queueKey, -maxLength, -1);
            metrics.dropped("redis-queue-limit", (int) Math.min(Integer.MAX_VALUE, size - maxLength));
        }
    }

    /** list 结构消费：LPOP key count 批量读取（Redis ≥ 6.2），pop 即删 */
    private void consumeList(HttpLogBatchHandler handler) {
        HttpLogProperties.Redis redis = properties.getRedis();
        while (running.get()) {
            try {
                List<String> payloads = redisTemplate.opsForList().leftPop(queueKey, redis.getReadCount());
                if (payloads == null || payloads.isEmpty()) {
                    sleepQuietly(redis.getBlockMs().toMillis());
                    continue;
                }
                List<HttpLogRecord> batch = new ArrayList<>(payloads.size());
                for (String payload : payloads) {
                    HttpLogRecord record = parse(payload);
                    if (record != null) {
                        batch.add(record);
                    }
                }
                handle(handler, batch, "redis-consume");
            } catch (Throwable t) {
                metrics.failed("redis-consume-loop", t);
                sleepQuietly(200);
            }
        }
    }

    /**
     * stream 结构消费：组就绪后先补自身 PEL 遗留（崩溃恢复），再读新消息；
     * 失败批按"允许丢"契约丢弃并计数；组丢失（NOGROUP）时自动重建
     */
    private void consumeStream(HttpLogBatchHandler handler) {
        HttpLogProperties.Redis redis = properties.getRedis();
        boolean groupReady = false;
        while (running.get()) {
            try {
                if (!groupReady) {
                    groupReady = ensureGroupSafely();
                    if (!groupReady) {
                        sleepQuietly(500);
                        continue;
                    }
                }
                List<MapRecord<String, Object, Object>> pending = redisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(redis.getReadCount()),
                        StreamOffset.create(queueKey, ReadOffset.from("0")));
                if (pending != null && !pending.isEmpty()) {
                    handleStreamRecords(pending, handler);
                }
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(redis.getReadCount()).block(redis.getBlockMs()),
                        StreamOffset.create(queueKey, ReadOffset.lastConsumed()));
                if (records != null && !records.isEmpty()) {
                    handleStreamRecords(records, handler);
                }
            } catch (Throwable t) {
                if (isNoGroup(t)) {
                    groupReady = false;
                    log.warn("[httplog]stream 消费组不存在（key 被清理？），将重建消费组");
                }
                metrics.failed("redis-stream-consume", t);
                sleepQuietly(200);
            }
        }
    }

    /** 处理一批 stream 记录：无论成功失败都 ACK（失败按丢弃计数；PEL 仅承载崩溃未处理，重启后补投） */
    private void handleStreamRecords(List<MapRecord<String, Object, Object>> records, HttpLogBatchHandler handler) {
        List<HttpLogRecord> batch = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Object payload = record.getValue().get(PAYLOAD_FIELD);
            HttpLogRecord parsed = payload == null ? null : parse(String.valueOf(payload));
            if (parsed != null) {
                batch.add(parsed);
            }
        }
        try {
            if (!batch.isEmpty()) {
                handler.onBatch(batch);
            }
        } catch (Throwable t) {
            metrics.failed("redis-consume", t);
            metrics.dropped("consume-failed", batch.size());
        }
        RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
        redisTemplate.opsForStream().acknowledge(queueKey, group, ids);
    }

    /** 建组（幂等）；key 尚不存在等失败返回 false 由调用方退避重试，异常不外抛、消费线程继续运行 */
    private boolean ensureGroupSafely() {
        try {
            redisTemplate.opsForStream().createGroup(queueKey, ReadOffset.latest(), group);
            groupWarned = false;
            return true;
        } catch (RuntimeException e) {
            if (containsBusyGroup(e)) {
                groupWarned = false;
                return true;
            }
            // 连续失败只告警一次（低频服务夜间无流量，逐次告警会持续刷 WARN），恢复后重置
            if (!groupWarned) {
                groupWarned = true;
                log.warn("[httplog]stream 消费组建组失败（key 可能尚不存在），转入退避重试、恢复后自动建组: {}",
                        e.getMessage());
            } else {
                log.debug("[httplog]stream 消费组建组仍失败，继续退避重试: {}", e.getMessage());
            }
            return false;
        }
    }

    /** 是否消费组不存在（组或 key 被清理） */
    private static boolean isNoGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("NOGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 异常链是否表示消费组已存在（BUSYGROUP） */
    private static boolean containsBusyGroup(Throwable throwable) {
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

    /** 回调处理一批并统计失败 */
    private void handle(HttpLogBatchHandler handler, List<HttpLogRecord> batch, String stage) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            handler.onBatch(batch);
        } catch (Exception e) {
            metrics.failed(stage, e);
            metrics.dropped("consume-failed", batch.size());
        }
    }

    /** 解析线协议消息 */
    private static HttpLogRecord parse(String payload) {
        try {
            return JsonUtils.parse(payload, HttpLogRecord.class);
        } catch (Exception e) {
            log.warn("[httplog]反序列化失败，丢弃该条: {}", e.getMessage());
            return null;
        }
    }

    /** 消费者名：配置优先；默认主机名——重启后同名，才能补投自身崩溃遗留的 PEL */
    private static String resolveConsumerName(HttpLogProperties.Redis redis) {
        if (StringUtils.isNotBlank(redis.getConsumerName())) {
            return redis.getConsumerName().trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "httplog-unknown-host";
        }
    }

    /** 休眠指定毫秒；被中断时恢复中断标志 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
