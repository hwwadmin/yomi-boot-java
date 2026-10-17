package com.yomi.boot.java.component.event.publish;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.ds.support.DbDuplicateKey;
import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.exception.EventBizException;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.component.event.repository.IEventOutboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 默认发件箱发布实现
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class EventPublisher implements IEventPublisher {

    /** 发件箱持久化 */
    private final IEventOutboxRepository outboxRepository;

    /** 服务名与 Topic 键 */
    private final EventKeys eventKeys;

    /** 载荷大小等限制 */
    private final EventProperties properties;

    /** 校验后以 PENDING 写入发件箱，重复 eventId 抛业务异常 */
    @Override
    public void publish(WireEvent event) {
        if (event == null || StringUtils.isBlank(event.getEventId()) || StringUtils.isBlank(event.getTopic())) {
            throw EventBizException.invalid("eventId 与 topic 不能为空");
        }
        String eventId = event.getEventId().trim();
        String topic = event.getTopic().trim();
        requireDeliverableSize(eventId, topic, event.getPayload());
        EventOutbox row = new EventOutbox()
                .setServiceName(eventKeys.getServiceName())
                .setEventId(eventId)
                .setTopic(topic)
                .setPayload(event.getPayload())
                .setStatus(OutboxStatus.PENDING);
        try {
            outboxRepository.insert(row);
        } catch (Exception e) {
            if (DbDuplicateKey.isDuplicateKey(e)) {
                throw EventBizException.duplicatePublish(eventKeys.getServiceName(), event.getEventId());
            }
            throw e;
        }
        log.info("[事件]发件箱写入成功 eventId={}, topic={}", eventId, topic);
    }

    /**
     * 超过 {@code yomi.event.max-payload-bytes} 的事件 Kafka 不会接受：
     * 发布时即失败，事件不会进入发件箱与 DLQ 重试
     */
    private void requireDeliverableSize(String eventId, String topic, String payload) {
        int maxBytes = properties.getMaxPayloadBytes();
        int actualBytes = serializedSize(new WireEvent(eventId, topic, payload));
        if (actualBytes > maxBytes) {
            throw EventBizException.tooLarge(eventId, actualBytes, maxBytes);
        }
    }

    /** 与投递侧同样的方式序列化一次；量出的字节数就是实际发往 Kafka 的消息体大小 */
    static int serializedSize(WireEvent event) {
        return JsonUtils.toJson(event).getBytes(StandardCharsets.UTF_8).length;
    }

}
