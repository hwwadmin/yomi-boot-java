package com.yomi.boot.java.component.ds.support;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 主库装配开关：配置了 {@code spring.datasource.url}，或宿主自带 {@code DataSource} Bean 时才接管主库。
 * <p>
 * 都不满足（宿主只用 ds 的工具类、不接数据库）时不装配主库四件套，避免凭空造一个连不上库的数据源；
 * 这种形态下组件的 DB 能力会在组件侧报错（"需要 SqlSessionFactory"/"未配置主数据源"），而不是这里静默失败。
 *
 * @author zwei
 */
public class MasterDataSourceConfiguredCondition extends SpringBootCondition {

    /** 主数据源就绪的判定键（Boot 标准键） */
    static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConditionMessage.Builder message = ConditionMessage.forCondition("DsMaster");
        if (StringUtils.hasText(context.getEnvironment().getProperty(DATASOURCE_URL_PROPERTY))) {
            return ConditionOutcome.match(message.because("已配置 " + DATASOURCE_URL_PROPERTY));
        }
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory != null && beanFactory.getBeanNamesForType(DataSource.class, false, false).length > 0) {
            return ConditionOutcome.match(message.because("宿主已提供 DataSource Bean"));
        }
        return ConditionOutcome.noMatch(message.because("既未配置 " + DATASOURCE_URL_PROPERTY + "，也没有 DataSource Bean"));
    }

}
