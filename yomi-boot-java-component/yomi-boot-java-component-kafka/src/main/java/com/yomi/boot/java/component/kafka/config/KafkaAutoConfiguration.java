package com.yomi.boot.java.component.kafka.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * kafka 组件自动装配：模块本身是纯静态工具（{@code KafkaUtils}），本配置只注册一个启动期自检。
 * <p>{@code @ConditionalOnClass} 必须保留：imports 会被任何 classpath 上有本 jar 的宿主发现，
 * 缺 spring-kafka 的宿主加载本类会 ClassNotFound</p>
 *
 * @author zwei
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaAutoConfiguration {

    /** 启动期多 {@link KafkaTemplate} 且无 {@code @Primary} 时打 WARN */
    @Bean
    public KafkaTemplateStructureCheck kafkaTemplateStructureCheck(
            ObjectProvider<KafkaTemplate<?, ?>> templateProvider) {
        return new KafkaTemplateStructureCheck(templateProvider);
    }

}
