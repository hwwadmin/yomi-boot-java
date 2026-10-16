package com.yomi.boot.java.component.ds.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.yomi.boot.java.component.ds.DsInfo;
import com.yomi.boot.java.component.ds.DsPreheat;
import com.yomi.boot.java.component.ds.support.DsDatabaseIdProvider;
import com.yomi.boot.java.component.ds.support.InstantJdbcTypeHandler;
import com.yomi.boot.java.component.ds.support.MybatisPlusChainChecker;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.Map;

/**
 * ds 组件自动装配：**只装配主数据源（master）**（见 {@link MasterDataSourceConfiguration}），
 * 附加库由业务系统自行定义与装配
 * <p>
 * 通过 {@code META-INF/spring/...AutoConfiguration.imports} 注册，宿主无需扫描 {@code com.yomi}；
 * 主库的 {@code spring.datasource.*} 由本组件自己注册的 {@code DataSourceProperties} 绑定，
 * 不依赖 Boot 的 {@code DataSourceAutoConfiguration} 是否在场（宿主排除它时主库照常可配）。
 * {@link MybatisPlusConfig}（分页 + 防全表写）无开关、随组件强制生效；
 * {@link InstantJdbcTypeHandler}（Instant 写库，MySQL/PG 通用）同样无开关；
 * 数据源预热保留 {@code yomi.ds.preheat.enabled} 开关。
 * <p>
 * <b>必须排在 MyBatis-Plus 的自动配置之前</b>：本组件接管主库工厂/模板后，MP 的同名 Bean 按类型让位
 * （{@code @ConditionalOnMissingBean}）；同时本组件要在 {@code MybatisPlusInnerInterceptorAutoConfiguration}
 * 之前注册拦截器链，否则 MP 的默认链会先占位（见 {@link MybatisPlusConfig} 注释）。
 * 兜底还有 {@link MybatisPlusChainChecker} 启动自检：容器里每个 {@code SqlSessionFactory} 都必须带分页与防全表写，
 * 且 Mapper 绑定不能"只写一半 ref"。
 *
 * @author zwei
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@AutoConfigureBefore(name = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration")
@Import({MybatisPlusConfig.class, MasterDataSourceConfiguration.class})
@EnableConfigurationProperties({MybatisPlusProperties.class, DataSourceProperties.class})
public class DsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DsPreheat.class)
    @ConditionalOnProperty(name = "yomi.ds.preheat.enabled", havingValue = "true", matchIfMissing = true)
    public DsPreheat dsPreheat(Map<String, DataSource> dataSourceBeans) {
        return new DsPreheat(dataSourceBeans);
    }

    @Bean
    @ConditionalOnMissingBean(DsInfo.class)
    public DsInfo dsInfo(Map<String, DataSource> dataSourceBeans) {
        return new DsInfo(dataSourceBeans);
    }

    /** 链 / 工厂 / Mapper 绑定自检：缺失即 fail-fast；随组件强制生效，无开关 */
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

    /**
     * 方言标识（{@code databaseId}）：MySQL→{@code mysql}、PostgreSQL→{@code pgsql}；
     * mapper XML 里无法双库通用的语句（如受限删除）按 dialect 各写一条，由 MyBatis 按当前数据源选择。
     * 主库由本组件注入工厂；业务自建附加库工厂请按模板注入本 Bean；识别不了方言即启动失败
     */
    @Bean
    @ConditionalOnMissingBean(DatabaseIdProvider.class)
    public DatabaseIdProvider databaseIdProvider() {
        return new DsDatabaseIdProvider();
    }

}
