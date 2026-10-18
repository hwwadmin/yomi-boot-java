package com.yomi.boot.java.component.ds.support;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/**
 * 主库 Bean 名独占校验：业务声明了 {@code masterXxx} 就是"替换主数据源的装配流程"，直接失败并给出路
 * <p>
 * 主数据源由 component-ds 独占装配（数据源/工厂/模板/事务管理器四件套都按 yml 创建）；
 * 业务能动的只有 {@code spring.datasource.*}（换池用 {@code spring.datasource.type}，池参数按推导前缀绑）。
 * 用条件而不是让 Spring 抛"Bean 定义冲突"：报错要点名政策与出路。
 *
 * @author zwei
 */
public class DsMasterExclusiveCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory == null) {
            return true;
        }
        List<String> occupied = DsMaster.beanNames().stream().filter(beanFactory::containsBean).toList();
        if (occupied.isEmpty()) {
            return true;
        }
        throw new IllegalStateException("[ds] 主数据源由 component-ds 独占装配，业务不得声明 " + occupied + "："
                + "主库请改到 yml（spring.datasource.url/username/password、spring.datasource.<池名>.*，"
                + "换池用 spring.datasource.type）；附加库请用业务自己的 Bean 名，"
                + "@MapperScan 写齐 sqlSessionFactoryRef 与 sqlSessionTemplateRef");
    }

}
