package com.yomi.boot.java.component.ds.config;

import com.yomi.boot.java.component.ds.DsInfo;
import com.yomi.boot.java.component.ds.DsPreheat;
import com.yomi.boot.java.component.ds.support.InstantJdbcTypeHandler;
import com.yomi.boot.java.component.ds.support.MybatisPlusChainChecker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.List;

/**
 * ds 组件自动装配
 * <p>
 * 通过 {@code META-INF/spring/...AutoConfiguration.imports} 注册，宿主无需扫描
 * {@code com.yomi}（继续扫描也不冲突：Spring 对同一配置类去重，
 * 带 {@code @ConditionalOnMissingBean} 的兜底 Bean 会让位给扫描注册的实例）。
 * {@link MybatisPlusConfig}（分页 + 防全表写）无开关、随组件强制生效；
 * {@link InstantJdbcTypeHandler}（Instant 写库，MySQL/PG 通用）同样无开关、强制生效；
 * 仅数据源预热保留 {@code yomi.boot.ds.preheat.enabled} 开关
 * <p>
 * <b>必须排在 MyBatis-Plus 的 {@code MybatisPlusInnerInterceptorAutoConfiguration} 之前</b>：
 * 两者都要注册 {@code MybatisPlusInterceptor}（都是"没有就注册"），MP 的默认链只挂宿主的
 * {@code InnerInterceptor}、不含分页与防全表写；若 MP 先注册，本组件的整链会静默让位
 * （宿主声明 {@code InnerInterceptor} 且不扫描 {@code com.yomi} 时触发，已实测复现）。
 * 兜底还有 {@link MybatisPlusChainChecker} 启动自检把"静默"变"响亮"。
 *
 * @author zwei
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@AutoConfigureBefore(name = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration")
@Import(MybatisPlusConfig.class)
public class DsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DsPreheat.class)
    @ConditionalOnProperty(name = "yomi.boot.ds.preheat.enabled", havingValue = "true", matchIfMissing = true)
    public DsPreheat dsPreheat(List<DataSource> dataSourceList) {
        return new DsPreheat(dataSourceList);
    }

    @Bean
    @ConditionalOnMissingBean(DsInfo.class)
    public DsInfo dsInfo(List<DataSource> dataSourceList) {
        return new DsInfo(dataSourceList);
    }

    /** 链自检：分页插件必须真的在拦截器链里（缺失即 fail-fast，不再静默）；随组件强制生效，无开关 */
    @Bean
    @ConditionalOnMissingBean(MybatisPlusChainChecker.class)
    public MybatisPlusChainChecker mybatisPlusChainChecker(ApplicationContext context) {
        return new MybatisPlusChainChecker(context);
    }

    /** Instant 写库处理器：覆盖 MyBatis 内置实现（其 setObject(Instant) 在 PG 驱动上直接报错）；MP 收集 TypeHandler Bean 注册 */
    @Bean
    @ConditionalOnMissingBean(InstantJdbcTypeHandler.class)
    public InstantJdbcTypeHandler instantJdbcTypeHandler() {
        return new InstantJdbcTypeHandler();
    }

}
