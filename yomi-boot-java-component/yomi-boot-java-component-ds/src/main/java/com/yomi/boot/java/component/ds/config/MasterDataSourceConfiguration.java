package com.yomi.boot.java.component.ds.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.yomi.boot.java.component.ds.support.ConditionalOnMissingBeanNames;
import com.yomi.boot.java.component.ds.support.DsCrossDbGuard;
import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.ds.support.DsSqlSessionFactoryBuilder;
import com.yomi.boot.java.component.ds.support.MasterDataSourceConfiguredCondition;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/**
 * 主库（master）装配：数据源 + 工厂 + 模板 + 事务管理器，四件套全部 {@code @Primary}
 * <p>
 * 于是"所有不指定名称的按类型注入"自然落在主库：组件与业务的 {@code @MapperScan}（不写
 * {@code sqlSessionFactoryRef}）、{@code @Transactional}（不写 {@code transactionManager}）、
 * {@code TransactionTemplate}、{@code JdbcTemplate} 都无需配置。附加库由业务系统自行定义与装配，
 * 用 {@code @MapperScan(basePackages = ..., sqlSessionFactoryRef = ..., sqlSessionTemplateRef = ...)}
 * 显式绑定（两个 ref 都要写，见 {@code MybatisPlusChainChecker} 的自检）。
 * <p>
 * 让位规则：配了 {@code spring.datasource.url} 才声明主数据源；四件套都是"宿主提供了同名 Bean（{@code masterXxx}）就让位"
 * （只按 Bean 名判断，附加库的同类型 Bean 不影响主库装配）。
 * 单独成类（而不是内嵌在 {@link DsAutoConfiguration} 里）：宿主的 {@code @ComponentScan} 会扫到
 * 自动配置类本身，内嵌类会与"成员类处理"通道重复注册同一个 Bean 名。
 *
 * @author zwei
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Conditional(MasterDataSourceConfiguredCondition.class)
@Import(DsSqlSessionFactoryBuilder.class)
public class MasterDataSourceConfiguration {

    /**
     * 主数据源：Boot 标准键 {@code spring.datasource.*}；**连接池类型可换**——
     * {@code spring.datasource.type} 指定实现（缺省 Hikari），池参数按类型推导前缀绑定
     * （{@code HikariDataSource}→{@code spring.datasource.hikari.*}、{@code DruidDataSource}→{@code spring.datasource.druid.*}、
     * 规则见 {@link #poolPropertiesPrefix}）；需要更特殊的构造（自定义池、JNDI、逻辑数据源）就自己声明同名
     * {@code masterDataSource} Bean，本组件让位（解析顺序见 {@link DsMaster}）
     */
    @Bean
    @Primary
    @ConditionalOnMissingBeanNames(DsMaster.DATASOURCE_BEAN)
    @ConditionalOnProperty(prefix = "spring.datasource", name = "url")
    public DataSource masterDataSource(DataSourceProperties properties, Environment environment) {
        DataSource dataSource = properties.initializeDataSourceBuilder().build();
        String prefix = poolPropertiesPrefix(dataSource.getClass());
        Binder.get(environment).bind(prefix, Bindable.ofInstance(dataSource));
        if (dataSource instanceof HikariDataSource hikari && hikari.getPoolName() == null) {
            // Hikari 未显式配置 pool-name 时用库名做池名（日志与监控里"哪个池是主库"一眼可辨）
            hikari.setPoolName(DsMaster.LIBRARY_NAME);
        }
        log.info("[ds] 主数据源连接池: {}（池参数前缀 {}）", dataSource.getClass().getName(), prefix);
        return dataSource;
    }

    /**
     * 连接池参数前缀：{@code spring.datasource.<池名>.*}，池名 = 实现类简单名去掉 {@code DataSource} 后缀、首字母小写
     * （{@code HikariDataSource}→{@code hikari}，与 Boot 一致；{@code DruidDataSource}→{@code druid}，
     * {@code TomcatDataSource}→{@code tomcat}）
     */
    static String poolPropertiesPrefix(Class<?> dataSourceType) {
        String simpleName = dataSourceType.getSimpleName();
        String name = simpleName.endsWith("DataSource")
                ? simpleName.substring(0, simpleName.length() - "DataSource".length()) : simpleName;
        if (name.isEmpty()) {
            return "spring.datasource.pool";
        }
        return "spring.datasource." + Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** 主数据源公告：只在这里打一次（事务管理器同样解析主数据源，不再重复公告） */
    private static void announceMasterDataSource(DsMaster.Resolved<DataSource> resolved) {
        if (DsMaster.DATASOURCE_BEAN.equals(resolved.beanName())) {
            log.info("[ds] 主数据源 = {}（component-ds 装配）", DsMaster.DATASOURCE_BEAN);
        } else {
            log.info("[ds] 主数据源 = 容器里唯一/@Primary 的 {}（宿主提供）", resolved.bean().getClass().getName());
        }
    }

    /** 主库工厂：装配口径逐条对齐 MP 的自动配置（见 {@link DsSqlSessionFactoryBuilder}） */
    @Bean
    @Primary
    @ConditionalOnMissingBeanNames(DsMaster.SQL_SESSION_FACTORY_BEAN)
    public SqlSessionFactory masterSqlSessionFactory(DsSqlSessionFactoryBuilder builder,
                                                     ListableBeanFactory beanFactory,
                                                     ObjectProvider<DatabaseIdProvider> databaseIdProvider,
                                                     @Value("${yomi.ds.transaction-guard:error}") String transactionGuard)
            throws Exception {
        DsCrossDbGuard.setEnabled(DsCrossDbGuard.parseEnabled(transactionGuard));
        DsMaster.Resolved<DataSource> resolved = DsMaster.resolveDataSource(beanFactory);
        announceMasterDataSource(resolved);
        return builder.build(DsMaster.LIBRARY_NAME, resolved.bean(), databaseIdProvider.getIfAvailable());
    }

    /** 主库模板：尊重 {@code mybatis-plus.executor-type}；不指定名称注入 SqlSessionTemplate 的组件/业务都取它 */
    @Bean
    @Primary
    @ConditionalOnMissingBeanNames(DsMaster.SQL_SESSION_TEMPLATE_BEAN)
    public SqlSessionTemplate masterSqlSessionTemplate(ListableBeanFactory beanFactory,
                                                       MybatisPlusProperties properties) {
        SqlSessionFactory factory = DsMaster.resolve(beanFactory, SqlSessionFactory.class,
                DsMaster.SQL_SESSION_FACTORY_BEAN, "主库 SqlSessionFactory").bean();
        ExecutorType executorType = properties.getExecutorType();
        return executorType == null ? new SqlSessionTemplate(factory)
                : new SqlSessionTemplate(factory, executorType);
    }

    /**
     * 主库事务管理器：{@code @Transactional} 不写 {@code transactionManager} 时走它；
     * 附加库请用 {@code @Transactional(transactionManager = "...")} 显式指定
     */
    @Bean
    @Primary
    @ConditionalOnMissingBeanNames(DsMaster.TRANSACTION_MANAGER_BEAN)
    public PlatformTransactionManager masterTransactionManager(ListableBeanFactory beanFactory) {
        DataSource dataSource = DsMaster.resolveDataSource(beanFactory).bean();
        return new DataSourceTransactionManager(dataSource);
    }

}
