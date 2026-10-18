package com.yomi.boot.java.component.ds.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.yomi.boot.java.component.ds.support.DsCrossDbGuard;
import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.ds.support.DsMasterExclusiveCondition;
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
 * <b>主数据源由本组件独占装配</b>：业务只能在 yml 里配（{@code spring.datasource.*}），不得声明
 * {@code masterXxx}（声明即启动失败，见 {@link DsMasterExclusiveCondition}），也不得用自建 DataSource 顶主库。
 * 所有不指定名称的按类型注入都落在主库：{@code @MapperScan}（不写 {@code sqlSessionFactoryRef}）、
 * {@code @Transactional}（不写 {@code transactionManager}）、{@code TransactionTemplate}、
 * {@code JdbcTemplate} 都无需配置；不写 ref 的 Mapper 落在别的工厂、附加库那套标了 {@code @Primary}
 * 之类的错位由启动期绑定审计（{@code MybatisPlusChainChecker}）拦下。
 * <p>
 * 附加库由业务系统自行定义与装配，用
 * {@code @MapperScan(basePackages = ..., sqlSessionFactoryRef = ..., sqlSessionTemplateRef = ...)}
 * 显式绑定（两个 ref 都要写），且不要给附加库那套标 {@code @Primary}。
 * <p>
 * 装配条件：配了 {@code spring.datasource.url} 才声明主库（不接数据库的宿主照常启动，主库设施要用时才失败）。
 * 独立成类：内嵌在 {@link DsAutoConfiguration} 里时，宿主的 {@code @ComponentScan} 会扫到自动配置类本身，
 * 内嵌类会与"成员类处理"通道重复注册同一个 Bean 名。
 *
 * @author zwei
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Conditional({DsMasterExclusiveCondition.class, MasterDataSourceConfiguredCondition.class})
@Import(DsSqlSessionFactoryBuilder.class)
public class MasterDataSourceConfiguration {

    /**
     * 主数据源：只走 yml（Boot 标准键 {@code spring.datasource.*}）；连接池类型可换——
     * {@code spring.datasource.type} 指定实现（缺省 Hikari），池参数按类型推导前缀绑定，规则见
     * {@link #poolPropertiesPrefix}（{@code HikariDataSource}→{@code spring.datasource.hikari.*}、
     * {@code DruidDataSource}→{@code spring.datasource.druid.*}）
     */
    @Bean
    @Primary
    public DataSource masterDataSource(DataSourceProperties properties, Environment environment) {
        DataSource dataSource = properties.initializeDataSourceBuilder().build();
        String prefix = poolPropertiesPrefix(dataSource.getClass());
        Binder.get(environment).bind(prefix, Bindable.ofInstance(dataSource));
        if (dataSource instanceof HikariDataSource hikari && hikari.getPoolName() == null) {
            // Hikari 未显式配置 pool-name 时用库名做池名（日志与监控里可区分主库）
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

    /** 主库工厂：装配口径逐条对齐 MP 的自动配置（见 {@link DsSqlSessionFactoryBuilder}） */
    @Bean
    @Primary
    public SqlSessionFactory masterSqlSessionFactory(DsSqlSessionFactoryBuilder builder,
                                                     ListableBeanFactory beanFactory,
                                                     ObjectProvider<DatabaseIdProvider> databaseIdProvider,
                                                     @Value("${yomi.ds.transaction-guard:error}") String transactionGuard)
            throws Exception {
        DsCrossDbGuard.setEnabled(DsCrossDbGuard.parseEnabled(transactionGuard));
        DataSource dataSource = DsMaster.requireDataSource(beanFactory);
        log.info("[ds] 主数据源 = {}（component-ds 装配）", DsMaster.DATASOURCE_BEAN);
        return builder.build(DsMaster.LIBRARY_NAME, dataSource, databaseIdProvider.getIfAvailable());
    }

    /** 主库模板：尊重 {@code mybatis-plus.executor-type}；不指定名称注入 SqlSessionTemplate 的组件/业务都取它 */
    @Bean
    @Primary
    public SqlSessionTemplate masterSqlSessionTemplate(ListableBeanFactory beanFactory,
                                                       MybatisPlusProperties properties) {
        SqlSessionFactory factory = DsMaster.require(beanFactory, SqlSessionFactory.class,
                DsMaster.SQL_SESSION_FACTORY_BEAN, "主库 SqlSessionFactory");
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
    public PlatformTransactionManager masterTransactionManager(ListableBeanFactory beanFactory) {
        return new DataSourceTransactionManager(DsMaster.requireDataSource(beanFactory));
    }

}
