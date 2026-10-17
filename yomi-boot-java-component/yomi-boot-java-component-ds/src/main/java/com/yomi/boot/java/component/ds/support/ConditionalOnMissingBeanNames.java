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
 * "没有指定名字的 Bean 时才装配"：只按 Bean 名判断，不看类型
 * <p>
 * 与 {@code @ConditionalOnMissingBean} 的区别：后者在 {@code @Bean} 方法上会连返回类型一起判（没有该名字
 * 的 Bean 且没有该类型的 Bean），宿主自建一个附加库工厂（类型相同、名字不同）就会连带跳过本组件的
 * 主库四件套；附加库由业务系统自行装配属常态，主库装配须不受其影响。
 *
 * @author zwei
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnMissingBeanNames.OnMissingBeanNamesCondition.class)
public @interface ConditionalOnMissingBeanNames {

    /** 这些 Bean 名都不存在时匹配（有任意一个存在就整体不装配，以宿主的为准） */
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
