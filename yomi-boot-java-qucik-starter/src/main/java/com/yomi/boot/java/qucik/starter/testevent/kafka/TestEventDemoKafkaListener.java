package com.yomi.boot.java.qucik.starter.testevent.kafka;

import com.yomi.boot.java.component.event.EventUtils;
import com.yomi.boot.java.component.event.consume.EventConsumeResult;
import com.yomi.boot.java.qucik.starter.testevent.TestEventTopics;
import com.yomi.boot.java.qucik.starter.testevent.service.TestEventDemoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 演示 Topic 的注解消费：{@link EventUtils#onMessage} 幂等 + 按结果手动 ack/重投。
 * <p>RETRY 必须用 {@code acknowledgment.nack(...)} 退回重投；仅"不提交 offset"不会让 Kafka 重投，
 * 同分区后续消息一旦提交，失败那条就被永久跳过
 *
 * @author zwei
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.event.consumer-enabled", havingValue = "true")
public class TestEventDemoKafkaListener {

    /** RETRY 重投前的退避时长 */
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

    /** 消费侧演示逻辑 */
    private final TestEventDemoService demoService;

    @KafkaListener(
            topics = TestEventTopics.DEMO,
            groupId = "${spring.application.name}-event-demo")
    /** 处理演示 Kafka 消息 */
    public void onDemoEvent(String body, Acknowledgment acknowledgment) {
        EventConsumeResult result = EventUtils.onMessage(body, demoService::onDemoMessage);
        log.info("[事件演示消费] result={}, bodyLength={}", result, body == null ? 0 : body.length());
        EventUtils.applyConsumeResult(result, acknowledgment::acknowledge,
                () -> acknowledgment.nack(RETRY_BACKOFF));
    }

}
