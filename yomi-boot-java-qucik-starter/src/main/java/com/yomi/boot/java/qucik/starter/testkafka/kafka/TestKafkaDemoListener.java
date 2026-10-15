package com.yomi.boot.java.qucik.starter.testkafka.kafka;

import com.yomi.boot.java.qucik.starter.testkafka.TestKafkaTopics;
import com.yomi.boot.java.qucik.starter.testkafka.service.TestKafkaFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka 演示消费（与 event 发件箱无关）。
 * <p>全局 {@code ack-mode: manual}：消费完必须显式 {@code acknowledge()}，否则位点永不提交、
 * 每次重启从头重放（对照 event 演示的 {@link com.yomi.boot.java.qucik.starter.testevent.kafka.TestEventDemoKafkaListener}）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestKafkaDemoListener {

    /** 消息处理委托给流程服务 */
    private final TestKafkaFlowService flowService;

    @KafkaListener(
            topics = TestKafkaTopics.DEMO,
            groupId = "${spring.application.name}-kafka-demo")
    /** 监听演示 Topic 消息 */
    public void onDemoMessage(String body, Acknowledgment acknowledgment) {
        flowService.onKafkaMessage(body);
        acknowledgment.acknowledge();
    }

}
