package com.yomi.boot.java.component.kafka;

import com.yomi.boot.java.core.utils.SpringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 Spring 容器获取 {@link KafkaTemplate}，并按 Bean 名称缓存。
 *
 * @author zwei
 */
final class KafkaTemplateHolder {

    /** 默认 {@link KafkaTemplate} 缓存 */
    private static volatile KafkaTemplate<String, Object> defaultKafkaTemplate;

    /** 按 Bean 名称缓存的 {@link KafkaTemplate} */
    private static final ConcurrentHashMap<String, KafkaTemplate<String, Object>> NAMED_KAFKA_TEMPLATES =
            new ConcurrentHashMap<>();

    private KafkaTemplateHolder() {
    }

    /**
     * @param templateBeanName 为空则取唯一/主 {@link KafkaTemplate} Bean
     */
    static KafkaTemplate<String, Object> resolve(String templateBeanName) {
        if (StringUtils.isBlank(templateBeanName)) {
            return defaultTemplate();
        }
        return NAMED_KAFKA_TEMPLATES.computeIfAbsent(
                templateBeanName,
                name -> SpringUtils.getBean(name, KafkaTemplate.class));
    }

    /** 懒加载并缓存默认 {@link KafkaTemplate} */
    private static KafkaTemplate<String, Object> defaultTemplate() {
        if (Objects.nonNull(defaultKafkaTemplate)) {
            return defaultKafkaTemplate;
        }
        synchronized (KafkaTemplateHolder.class) {
            if (Objects.nonNull(defaultKafkaTemplate)) {
                return defaultKafkaTemplate;
            }
            defaultKafkaTemplate = resolveDefaultFromContext();
        }
        return defaultKafkaTemplate;
    }

    /**
     * 从 Spring 容器解析默认 {@link KafkaTemplate}：唯一候选或 {@code @Primary} 直接返回；
     * 多候选且无 {@code @Primary} 时抛出带候选列表与配置说明的异常。
     */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> resolveDefaultFromContext() {
        Map<String, KafkaTemplate<String, Object>> candidates =
                (Map<String, KafkaTemplate<String, Object>>) (Map<?, ?>) SpringUtils.getBean2Map(KafkaTemplate.class);
        KafkaTemplate<String, Object> primary = (KafkaTemplate<String, Object>) SpringUtils.getBeanNoEx(KafkaTemplate.class);
        return resolveDefault(candidates, primary);
    }

    static KafkaTemplate<String, Object> resolveDefault(Map<String, KafkaTemplate<String, Object>> candidates,
                                                        KafkaTemplate<String, Object> primaryOrNull) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException(
                    "发送 Kafka 消息需要至少一个 KafkaTemplate Bean：请配置 spring.kafka 或自定义 KafkaTemplate Bean");
        }
        if (candidates.size() == 1) {
            return candidates.values().iterator().next();
        }
        if (primaryOrNull != null) {
            return primaryOrNull;
        }
        throw new IllegalStateException("存在多个 KafkaTemplate Bean " + candidates.keySet()
                + " 且没有 @Primary：不指定 beanName 的发送无法确定用哪个；"
                + "请用 KafkaUtils.sync().template(beanName) 指定（event 组件对应 yomi.event.kafka-template-bean-name），"
                + "或给其中一个 Bean 加 @Primary");
    }

    /** 单测注入 mock 前清空缓存 */
    static void clearTemplateCache() {
        synchronized (KafkaTemplateHolder.class) {
            defaultKafkaTemplate = null;
            NAMED_KAFKA_TEMPLATES.clear();
        }
    }

}
