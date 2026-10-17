package com.yomi.boot.java.component.event.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * event 发布侧的 KafkaTemplate 启动期校验：启动时即确认模板可用，
 * 配置有歧义时在报错里给出候选与配置键
 */
class EventKafkaTemplateCheckTest {

    @Test
    void failsWhenNoTemplate() {
        new ApplicationContextRunner().run(context -> {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> EventAutoConfiguration.requireKafkaTemplate(properties(null), context, provider(context)));

            assertTrue(ex.getMessage().contains("spring.kafka"), ex.getMessage());
        });
    }

    @Test
    void passesWithSingleTemplate() {
        runner("only").run(context -> assertDoesNotThrow(
                () -> EventAutoConfiguration.requireKafkaTemplate(properties(null), context, provider(context))));
    }

    @Test
    void failsWhenAmbiguousWithoutPrimary() {
        runner("t1", "t2").run(context -> {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> EventAutoConfiguration.requireKafkaTemplate(properties(null), context, provider(context)));

            String message = ex.getMessage();
            assertTrue(message.contains("t1") && message.contains("t2"), "报错应点名候选: " + message);
            assertTrue(message.contains("yomi.event.kafka-template-bean-name"), "报错应给出配置键: " + message);
            assertTrue(message.contains("@Primary"), "报错应给出加 @Primary 的出路: " + message);
        });
    }

    @Test
    void passesWithPrimaryTemplate() {
        new ApplicationContextRunner().withUserConfiguration(PrimaryCase.class)
                .run(context -> assertDoesNotThrow(
                        () -> EventAutoConfiguration.requireKafkaTemplate(properties(null), context, provider(context))));
    }

    @Test
    void validatesConfiguredBeanName() {
        runner("t1", "t2").run(context -> {
            assertDoesNotThrow(() -> EventAutoConfiguration.requireKafkaTemplate(
                    properties("t2"), context, provider(context)), "配了存在的 bean 名应通过");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> EventAutoConfiguration
                    .requireKafkaTemplate(properties("missing"), context, provider(context)));
            assertTrue(ex.getMessage().contains("missing"), "报错应点名找不到的名字: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("t1") && ex.getMessage().contains("t2"),
                    "报错应列出候选: " + ex.getMessage());
        });
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner */
    private static ApplicationContextRunner runner(String... beanNames) {
        ApplicationContextRunner runner = new ApplicationContextRunner();
        for (String beanName : beanNames) {
            Supplier<KafkaTemplate> supplier = EventKafkaTemplateCheckTest::stubTemplate;
            runner = runner.withBean(beanName, KafkaTemplate.class, supplier);
        }
        return runner;
    }

    /** 不连 broker 的模板实例：构造期不建连接 */
    private static KafkaTemplate<String, Object> stubTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of()));
    }

    /** 构造测试用 EventProperties */
    private static EventProperties properties(String beanName) {
        EventProperties properties = new EventProperties();
        properties.setKafkaTemplateBeanName(beanName);
        return properties;
    }

    /** 从 ApplicationContext 取 KafkaTemplate 的 ObjectProvider */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaTemplate<String, Object>> provider(ApplicationContext context) {
        return (ObjectProvider<KafkaTemplate<String, Object>>) (ObjectProvider<?>) context
                .getBeanProvider(KafkaTemplate.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryCase {

        @Bean
        @Primary
        KafkaTemplate<String, Object> primaryTemplate() {
            return stubTemplate();
        }

        @Bean
        KafkaTemplate<String, Object> otherTemplate() {
            return stubTemplate();
        }
    }

}
