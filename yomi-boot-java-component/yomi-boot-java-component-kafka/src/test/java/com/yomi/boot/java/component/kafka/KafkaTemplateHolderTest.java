package com.yomi.boot.java.component.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 默认 KafkaTemplate 解析：唯一候选或 @Primary 可用；多候选无 @Primary 时启动期给出明确报错。
 */
class KafkaTemplateHolderTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> first = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> second = mock(KafkaTemplate.class);

    @Test
    void missingCandidatesFailsWithConfigHint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> KafkaTemplateHolder.resolveDefault(Map.of(), null));

        assertTrue(ex.getMessage().contains("spring.kafka"), ex.getMessage());
    }

    @Test
    void singleCandidateIsUsed() {
        assertSame(first, KafkaTemplateHolder.resolveDefault(Map.of("only", first), null));
    }

    @Test
    void primaryWinsAmongMultipleCandidates() {
        assertSame(second, KafkaTemplateHolder.resolveDefault(Map.of("t1", first, "t2", second), second));
    }

    @Test
    void ambiguousCandidatesFailWithActionableMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> KafkaTemplateHolder.resolveDefault(Map.of("t1", first, "t2", second), null));

        String message = ex.getMessage();
        assertTrue(message.contains("t1") && message.contains("t2"), "报错应点名全部候选: " + message);
        assertTrue(message.contains("template(beanName)"), "报错应给出指定 beanName 的出路: " + message);
        assertTrue(message.contains("@Primary"), "报错应给出加 @Primary 的出路: " + message);
        assertTrue(message.contains("kafka-template-bean-name"), "报错应提示 event 组件的配置键: " + message);
    }

}
