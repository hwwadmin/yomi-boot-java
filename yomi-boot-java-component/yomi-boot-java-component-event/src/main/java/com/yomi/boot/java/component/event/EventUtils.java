package com.yomi.boot.java.component.event;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.event.consume.EventConsumeResult;
import com.yomi.boot.java.component.event.consume.EventHandler;
import com.yomi.boot.java.component.event.consume.EventInboxGuard;
import com.yomi.boot.java.component.event.consume.WireEventParser;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.component.event.publish.IEventPublisher;
import com.yomi.boot.java.core.utils.SpringUtils;

import java.util.Optional;
import java.util.UUID;

/**
 * 事件组件静态入口，便于在 Service / {@code @KafkaListener} 内函数式调用。
 * <p>
 * 线协议为 {@link WireEvent} JSON（{@code eventId、topic、payload}），Kafka message key 使用 {@code eventId}。
 * </p>
 * <ul>
 *   <li>发布：需 {@code yomi.event.publisher-enabled=true}，{@link #publish} 须在 Spring 事务内调用（写入发件箱）</li>
 *   <li>消费：需 {@code yomi.event.consumer-enabled=true}，{@link #onMessage} 经收件箱幂等（分布式锁 + inbox）</li>
 *   <li>后台投递 Kafka 由发件箱 relay 完成，另需 {@code yomi.roundrobin.enabled=true} 与 Kafka 配置</li>
 * </ul>
 *
 * @author zwei
 */
public final class EventUtils {

    private EventUtils() {
    }

    /** 生成新 eventId，可作为业务侧幂等键或 Kafka key */
    public static String newEventId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 构造线协议事件（显式 eventId）
     *
     * @param eventId 自定义事件 ID：必须在<b>同一 topic 的所有生产者之间唯一</b>（消费侧幂等键是
     *                {@code service_name + eventId}，不同生产者撞 id 的消息会被当作重复消息跳过）；一般用 {@link #newEventId()}
     */
    public static WireEvent wire(String eventId, String topic, String payload) {
        return WireEvent.builder()
                .eventId(eventId)
                .topic(topic)
                .payload(payload)
                .build();
    }

    /** 构造线协议事件（自动生成 eventId） */
    public static WireEvent wire(String topic, String payload) {
        return wire(newEventId(), topic, payload);
    }

    /** 构造线协议事件；{@code payload} 为对象时序列化为 JSON 字符串（eventId 唯一性要求见 {@link #wire(String, String, String)}） */
    public static WireEvent wire(String eventId, String topic, Object payload) {
        return wire(eventId, topic, payload == null ? null : JsonUtils.toJson(payload));
    }

    /** 解析消息体，要求含非空 eventId */
    public static Optional<WireEvent> parse(String body) {
        return WireEventParser.parse(body, true);
    }

    /**
     * 解析消息体
     *
     * @param requireEventId 为 true 时 eventId 缺失或空白则返回 empty
     */
    public static Optional<WireEvent> parse(String body, boolean requireEventId) {
        return WireEventParser.parse(body, requireEventId);
    }

    /** 解析消息体（UTF-8），要求含非空 eventId */
    public static Optional<WireEvent> parse(byte[] body) {
        return WireEventParser.parse(body, true);
    }

    /** 将 {@link WireEvent} 序列化为 Kafka 消息 JSON 字符串 */
    public static String toJson(WireEvent event) {
        return JsonUtils.toJson(event);
    }

    /**
     * 写入发件箱（状态 PENDING，由 relay 异步投递 Kafka）
     * <p>须在 Spring 事务内调用；同一 {@code eventId} 重复写入会抛业务异常</p>
     * <p>显式 eventId 必须在<b>同一 topic 的所有生产者之间唯一</b>，撞 id 的消息会被消费侧当重复消息跳过
     * （幂等键 = {@code service_name + eventId}）</p>
     */
    public static void publish(WireEvent event) {
        publisher().publish(event);
    }

    /** 写入发件箱（显式 eventId；唯一性要求见 {@link #publish(WireEvent)}） */
    public static void publish(String eventId, String topic, String payload) {
        publish(wire(eventId, topic, payload));
    }

    /** 写入发件箱（自动生成 eventId） */
    public static void publish(String topic, String payload) {
        publish(wire(topic, payload));
    }

    /**
     * 幂等消费：解析失败返回 {@link EventConsumeResult#DROP}，重复 eventId 不执行业务仍返回 SUCCESS
     *
     * @param body    Kafka 消息体 JSON
     * @param handler 仅首次成功占位 inbox 后执行
     */
    public static EventConsumeResult onMessage(String body, EventHandler handler) {
        return inboxGuard().onMessage(body, handler);
    }

    /** 同 {@link #onMessage(String, EventHandler)}，消息体为字节数组 */
    public static EventConsumeResult onMessage(byte[] body, EventHandler handler) {
        return inboxGuard().onMessage(body, handler);
    }

    /**
     * 非幂等消费：不写 inbox；业务异常仅打日志，仍返回 SUCCESS
     * <p>仅用于可丢弃或可补偿场景（失败不重投）；消息带 eventId 时会 WARN，一般应使用
     * {@link #onMessage(String, EventHandler)} 幂等路径</p>
     */
    public static EventConsumeResult onMessageNormal(String body, EventHandler handler) {
        return inboxGuard().onMessageNormal(body, handler);
    }

    /**
     * 将消费结果映射为 ack / 重试动作（不依赖 spring-kafka 类型）
     *
     * @param ack   SUCCESS、DROP 时执行（如提交 offset）
     * @param retry RETRY 时执行；退回重投需显式动作（如 {@code acknowledgment.nack(...)}）：
     *              manual ack 模式下提交的是已确认的最大 offset+1，同分区后续消息一旦提交，
     *              失败那条就被跳过
     */
    public static void applyConsumeResult(EventConsumeResult result, Runnable ack, Runnable retry) {
        switch (result) {
            case SUCCESS, DROP -> {
                if (ack != null) {
                    ack.run();
                }
            }
            case RETRY -> {
                if (retry != null) {
                    retry.run();
                }
            }
        }
    }

    /** 获取发件箱发布器 Bean（publisher-enabled） */
    public static IEventPublisher publisher() {
        return SpringUtils.getBean(IEventPublisher.class);
    }

    /** 获取收件箱幂等守卫 Bean（consumer-enabled） */
    public static EventInboxGuard inboxGuard() {
        return SpringUtils.getBean(EventInboxGuard.class);
    }

}
