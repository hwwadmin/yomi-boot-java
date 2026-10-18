package com.yomi.boot.java.component.ds.support;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 主库装配开关：配了 {@code spring.datasource.url} 才装配主库四件套
 * <p>
 * 主库只认 yml（业务不能自带 DataSource 顶主库，也不能声明 {@code masterXxx}，见
 * {@link DsMasterExclusiveCondition}）；没配 url 时不凭空创建连不上库的数据源，
 * 组件的 DB 能力会在组件侧报错（"未装配主数据源（Bean masterDataSource）"）。
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
        return ConditionOutcome.noMatch(message.because("未配置 " + DATASOURCE_URL_PROPERTY));
    }

}
