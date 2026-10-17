package com.yomi.boot.java.component.kafka.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * kafka 模块启动期自检：多 KafkaTemplate 且无 @Primary 时打 WARN，启动不失败
 */
class KafkaAutoConfigurationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void importsFileDeclaresAutoConfiguration() throws Exception {
        try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(IMPORTS_RESOURCE)) {
            assertNotNull(in, "缺少 " + IMPORTS_RESOURCE);
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(content.contains(KafkaAutoConfiguration.class.getName()), content);
        }
    }

    @Test
    void warnsWhenMultipleTemplatesWithoutPrimary() {
        String logs = runAndCollect(WarnCase.class);

        assertTrue(logs.contains("多个 KafkaTemplate Bean") || logs.contains("个 KafkaTemplate Bean"),
                "应提示多候选歧义: " + logs);
        assertTrue(logs.contains("@Primary"), logs);
        assertTrue(logs.contains("kafka-template-bean-name"), "应给出 event 组件的配置键: " + logs);
    }

    @Test
    void silentWithPrimaryTemplate() {
        assertFalse(runAndCollect(PrimaryCase.class).contains("KafkaTemplate Bean 且没有 @Primary"),
                "有 @Primary 时不应告警");
    }

    @Test
    void silentWithSingleTemplate() {
        assertFalse(runAndCollect(SingleCase.class).contains("且没有 @Primary"), "单候选不应告警");
    }

    /** 执行 Runner 并收集日志 */
    private static String runAndCollect(Class<?> userConfig) {
        ListAppender<ILoggingEvent> appender = attach();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                    .withUserConfiguration(userConfig)
                    .run(context -> assertNull(context.getStartupFailure(),
                            () -> "自检不应让启动失败: " + context.getStartupFailure()));
        } finally {
            detach(appender);
        }
        return render(appender);
    }

    /** 挂载测试用 ListAppender */
    private static ListAppender<ILoggingEvent> attach() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
        return appender;
    }

    /** 卸载测试用 ListAppender */
    private static void detach(ListAppender<ILoggingEvent> appender) {
        logger().detachAppender(appender);
    }

    /** 获取被测类的 Logger */
    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(KafkaTemplateStructureCheck.class);
    }

    /** 渲染日志事件为字符串 */
    private static String render(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    /** 两个模板、无 @Primary */
    @Configuration(proxyBeanMethods = false)
    static class WarnCase {

        @Bean
        KafkaTemplate<String, Object> firstTemplate() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        KafkaTemplate<String, Object> secondTemplate() {
            return mock(KafkaTemplate.class);
        }
    }

    /** 单模板 */
    @Configuration(proxyBeanMethods = false)
    static class SingleCase {

        @Bean
        KafkaTemplate<String, Object> onlyTemplate() {
            return mock(KafkaTemplate.class);
        }
    }

    /** 两个模板、一个 @Primary */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryCase {

        @Bean
        @Primary
        KafkaTemplate<String, Object> primaryTemplate() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        KafkaTemplate<String, Object> otherTemplate() {
            return mock(KafkaTemplate.class);
        }
    }

}
