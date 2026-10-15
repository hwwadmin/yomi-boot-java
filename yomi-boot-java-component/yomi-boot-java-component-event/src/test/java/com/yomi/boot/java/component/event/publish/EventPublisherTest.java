package com.yomi.boot.java.component.event.publish;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.exception.EventErrorCode;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.component.event.repository.EventOutboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发布端大小校验：Kafka 不会接受超限事件，必须在写发件箱之前失败，
 * 而不是让它写进去、反复重试、最终在 DLQ 里耗尽重试次数
 */
class EventPublisherTest {

    private static final int LIMIT = 1_000;
    private static final EventKeys KEYS = new EventKeys("svc");

    /** 记录入库的行，使测试不依赖数据库 */
    static class RecordingOutboxRepository extends EventOutboxRepository {
        EventOutbox saved;

        RecordingOutboxRepository() {
            super(null);
        }

        @Override
        public void insert(EventOutbox row) {
            this.saved = row;
        }
    }

    private final RecordingOutboxRepository repository = new RecordingOutboxRepository();

    /** 构造 EventPublisher 测试实例 */
    private EventPublisher publisher(int maxPayloadBytes) {
        EventProperties properties = new EventProperties();
        properties.setMaxPayloadBytes(maxPayloadBytes);
        return new EventPublisher(repository, KEYS, properties);
    }

    @Test
    void sizeIsMeasuredOnSerializedEventIncludingEnvelope() {
        int envelope = EventPublisher.serializedSize(new WireEvent("e1", "t1", ""));
        assertEquals(envelope + 100,
                EventPublisher.serializedSize(new WireEvent("e1", "t1", "x".repeat(100))));
    }

    @Test
    void allowsEventExactlyAtLimit() {
        int envelope = EventPublisher.serializedSize(new WireEvent("e", "t", ""));
        String payload = "x".repeat(LIMIT - envelope);
        assertEquals(LIMIT, EventPublisher.serializedSize(new WireEvent("e", "t", payload)));

        publisher(LIMIT).publish(new WireEvent("e", "t", payload));

        assertNotNull(repository.saved);
        assertEquals("svc", repository.saved.getServiceName());
        assertEquals("e", repository.saved.getEventId());
        assertEquals(OutboxStatus.PENDING, repository.saved.getStatus());
    }

    @Test
    void rejectsOversizedEventBeforeInsert() {
        BizException ex = assertThrows(BizException.class,
                () -> publisher(LIMIT).publish(new WireEvent("e1", "t1", "x".repeat(LIMIT))));

        assertEquals(EventErrorCode.EVENT_TOO_LARGE, ex.getErrorCode());
        assertNull(repository.saved, "超限事件不应写入发件箱");
    }

    @Test
    void rejectsNonPositiveLimitAtStartup() {
        EventProperties properties = new EventProperties();
        properties.setPublisherEnabled(true);
        properties.setMaxPayloadBytes(0);

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
