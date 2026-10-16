package com.yomi.boot.java.component.ds.support;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * "没有指定名字的 Bean 时才装配"：**只按 Bean 名判断**，不看类型
 * <p>
 * 为什么不用 {@code @ConditionalOnMissingBean}：它在 {@code @Bean} 方法上会连方法的返回类型一起判
 * （"没有该名字的 Bean <b>且</b> 没有该类型的 Bean"），于是宿主自建一个**附加库**工厂（类型相同、名字不同）
 * 就会把本组件的主库四件套顶掉——那是静默的装配缺失。附加库是业务系统自行装配的常态，必须让主库装配不受影响。
 *
 * @author zwei
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnMissingBeanNames.OnMissingBeanNamesCondition.class)
public @interface ConditionalOnMissingBeanNames {

    /** 这些 Bean 名都不存在时匹配（只要有一个存在就整体让位给宿主） */
    String[] value();

    /** 仅按 Bean 名判断"缺失"的条件实现 */
    class OnMissingBeanNamesCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String[] names = (String[]) metadata.getAnnotationAttributes(
                    ConditionalOnMissingBeanNames.class.getName()).get("value");
            ListableBeanFactory beanFactory = context.getBeanFactory();
            if (beanFactory == null || names == null) {
                return true;
            }
            for (String name : names) {
                if (beanFactory.containsBean(name)) {
                    return false;
                }
            }
            return true;
        }
    }

}
