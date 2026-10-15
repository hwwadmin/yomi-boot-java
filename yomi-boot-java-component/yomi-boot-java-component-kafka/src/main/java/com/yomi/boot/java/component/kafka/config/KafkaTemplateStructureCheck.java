package com.yomi.boot.java.component.kafka.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

/**
 * KafkaTemplate 结构自检：启动期若存在多个 KafkaTemplate 且无 @Primary，输出 WARN。
 * 此时 {@code KafkaUtils.sync()/async()} 默认发送需通过 {@code template(beanName)} 指定 Bean。
 * <p>只提示不失败：宿主可能每次都显式 {@code template(beanName)}，多候选本身不是错误；
 * 需要硬约束的组件（如 event 发布侧）在自己的启动校验里做强校验</p>
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaTemplateStructureCheck implements SmartInitializingSingleton {

    /** 容器中全部 KafkaTemplate（用于多 Bean 结构检查） */
    private final ObjectProvider<KafkaTemplate<?, ?>> templateProvider;

    /** 单例就绪后：多个 KafkaTemplate 且无 @Primary 时打 WARN */
    @Override
    public void afterSingletonsInstantiated() {
        List<KafkaTemplate<?, ?>> candidates = templateProvider.orderedStream().toList();
        if (candidates.size() <= 1 || templateProvider.getIfUnique() != null) {
            return;
        }
        log.warn("检测到 {} 个 KafkaTemplate Bean 且没有 @Primary：不指定 beanName 的发送（KafkaUtils.sync()/async() 默认用法）"
                        + "会在发送时失败；请用 template(beanName) 指定（event 组件对应 yomi.event.kafka-template-bean-name），"
                        + "或给其中一个 Bean 加 @Primary",
                candidates.size());
    }

}
