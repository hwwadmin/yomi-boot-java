package com.yomi.boot.java.component.ds.support;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.autoconfigure.SpringBootVFS;
import com.baomidou.mybatisplus.autoconfigure.SqlSessionFactoryBeanCustomizer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.AnnotationHandler;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.handlers.PostInitTableInfoHandler;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 主库 SqlSessionFactory 构建器：逐条对齐 MP 的 {@code MybatisPlusAutoConfiguration#sqlSessionFactory(DataSource)}
 * <p>
 * 本组件接管主库工厂后，MP 的 {@code MybatisPlusAutoConfiguration} 的同名 Bean 因
 * {@code @ConditionalOnMissingBean} 跳过创建，所以本类挂的东西与它保持一致：配置（configuration/configLocation）、
 * 拦截器链（分页 + 防全表 + 宿主 {@code InnerInterceptor}）、{@code TypeHandler}（含 Instant 处理器）、
 * 方言 provider、{@code mapper-locations}、{@code GlobalConfig} 的各类处理器注入。
 * <b>升级 MyBatis-Plus 时必须回读该方法并同步本类</b>；{@link MybatisPlusChainChecker} 做启动期自检。
 * <p>
 * 与 MP 的差异：{@link GlobalConfig} 每个工厂一份副本。MP 在 {@code GlobalConfig} 里放了
 * "mapper 已注入"缓存（{@code mapperRegistryCache}），多工厂共享同一实例时，先在别的工厂注册过的 Mapper
 * 在本工厂会被跳过注入（CRUD 语句缺失 → 运行期 {@code Invalid bound statement}）。
 *
 * @author zwei
 */
@Slf4j
public class DsSqlSessionFactoryBuilder {

    /** MP 配置（与 MP 自动配置读同一份，因此 {@code mybatis-plus.*} 全部配置项继续生效） */
    private final MybatisPlusProperties properties;

    /** 用于设置 {@code applicationContext}（XML 里的 ${} 变量、VFS 等依赖它） */
    private final ApplicationContext applicationContext;

    /** 容器内全部 {@link Interceptor}（含 ds 的分页链与宿主的 InnerInterceptor 组装成的链） */
    private final ObjectProvider<Interceptor[]> interceptors;

    /** 容器内全部 {@link TypeHandler}（含 {@code InstantJdbcTypeHandler}） */
    private final ObjectProvider<TypeHandler[]> typeHandlers;

    /** 容器内全部 {@link LanguageDriver} */
    private final ObjectProvider<LanguageDriver[]> languageDrivers;

    /** 宿主的 {@link ConfigurationCustomizer} */
    private final ObjectProvider<ConfigurationCustomizer> configurationCustomizers;

    /** 宿主的 {@link SqlSessionFactoryBeanCustomizer} */
    private final ObjectProvider<SqlSessionFactoryBeanCustomizer> sqlSessionFactoryBeanCustomizers;

    /** 宿主的 {@link TransactionFactory} */
    private final ObjectProvider<TransactionFactory> transactionFactory;

    /** 宿主的 {@link MetaObjectHandler}（填充/更新审计） */
    private final ObjectProvider<MetaObjectHandler> metaObjectHandler;

    /** 宿主的 {@link AnnotationHandler}（MP 注解解析扩展） */
    private final ObjectProvider<AnnotationHandler> annotationHandler;

    /** 宿主的 {@link PostInitTableInfoHandler} */
    private final ObjectProvider<PostInitTableInfoHandler> postInitTableInfoHandler;

    /** 宿主的 {@link IKeyGenerator} */
    private final ObjectProvider<IKeyGenerator> keyGenerators;

    /** 宿主的 {@link ISqlInjector} */
    private final ObjectProvider<ISqlInjector> sqlInjector;

    /** 宿主的 {@link IdentifierGenerator} */
    private final ObjectProvider<IdentifierGenerator> identifierGenerator;

    public DsSqlSessionFactoryBuilder(MybatisPlusProperties properties, ApplicationContext applicationContext,
                                     ObjectProvider<Interceptor[]> interceptors,
                                     ObjectProvider<TypeHandler[]> typeHandlers,
                                     ObjectProvider<LanguageDriver[]> languageDrivers,
                                     ObjectProvider<ConfigurationCustomizer> configurationCustomizers,
                                     ObjectProvider<SqlSessionFactoryBeanCustomizer> sqlSessionFactoryBeanCustomizers,
                                     ObjectProvider<TransactionFactory> transactionFactory,
                                     ObjectProvider<MetaObjectHandler> metaObjectHandler,
                                     ObjectProvider<AnnotationHandler> annotationHandler,
                                     ObjectProvider<PostInitTableInfoHandler> postInitTableInfoHandler,
                                     ObjectProvider<IKeyGenerator> keyGenerators,
                                     ObjectProvider<ISqlInjector> sqlInjector,
                                     ObjectProvider<IdentifierGenerator> identifierGenerator) {
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.interceptors = interceptors;
        this.typeHandlers = typeHandlers;
        this.languageDrivers = languageDrivers;
        this.configurationCustomizers = configurationCustomizers;
        this.sqlSessionFactoryBeanCustomizers = sqlSessionFactoryBeanCustomizers;
        this.transactionFactory = transactionFactory;
        this.metaObjectHandler = metaObjectHandler;
        this.annotationHandler = annotationHandler;
        this.postInitTableInfoHandler = postInitTableInfoHandler;
        this.keyGenerators = keyGenerators;
        this.sqlInjector = sqlInjector;
        this.identifierGenerator = identifierGenerator;
    }

    /**
     * 为一个库（当前只有主库）构建 SqlSessionFactory
     *
     * @param libraryName         库名（日志与跨库守卫用）
     * @param dataSource          该库数据源
     * @param databaseIdProvider  方言 provider（可为 null；本组件注册的实例按活连接解析 MySQL→mysql、PG→pgsql）
     */
    public SqlSessionFactory build(String libraryName, DataSource dataSource, DatabaseIdProvider databaseIdProvider)
            throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setVfs(SpringBootVFS.class);
        factory.setApplicationContext(applicationContext);
        if (StringUtils.hasText(properties.getConfigLocation())) {
            factory.setConfigLocation(applicationContext.getResource(properties.getConfigLocation()));
        }
        applyConfiguration(factory);
        if (properties.getConfigurationProperties() != null) {
            factory.setConfigurationProperties(properties.getConfigurationProperties());
        }
        Interceptor[] plugins = plugins(libraryName, dataSource);
        factory.setPlugins(plugins);
        if (databaseIdProvider != null) {
            factory.setDatabaseIdProvider(databaseIdProvider);
        }
        if (StringUtils.hasLength(properties.getTypeAliasesPackage())) {
            factory.setTypeAliasesPackage(properties.getTypeAliasesPackage());
        }
        if (properties.getTypeAliasesSuperType() != null) {
            factory.setTypeAliasesSuperType(properties.getTypeAliasesSuperType());
        }
        if (StringUtils.hasLength(properties.getTypeHandlersPackage())) {
            factory.setTypeHandlersPackage(properties.getTypeHandlersPackage());
        }
        TypeHandler[] typeHandlerBeans = typeHandlers.getIfAvailable();
        if (!ObjectUtils.isEmpty(typeHandlerBeans)) {
            factory.setTypeHandlers(typeHandlerBeans);
        }
        Resource[] mapperLocations = properties.resolveMapperLocations();
        if (!ObjectUtils.isEmpty(mapperLocations)) {
            factory.setMapperLocations(mapperLocations);
        }
        transactionFactory.ifAvailable(factory::setTransactionFactory);
        LanguageDriver[] languageDriverBeans = languageDrivers.getIfAvailable();
        if (!ObjectUtils.isEmpty(languageDriverBeans)) {
            factory.setScriptingLanguageDrivers(languageDriverBeans);
        }
        if (properties.getDefaultScriptingLanguageDriver() != null) {
            factory.setDefaultScriptingLanguageDriver(properties.getDefaultScriptingLanguageDriver());
        }
        sqlSessionFactoryBeanCustomizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        factory.setGlobalConfig(globalConfig());
        SqlSessionFactory built = factory.getObject();
        log.info("[ds] {} 库 SqlSessionFactory 就绪: mapper XML {} 个, 插件 {} 个, 方言 {}",
                libraryName, ObjectUtils.isEmpty(mapperLocations) ? 0 : mapperLocations.length,
                plugins.length, databaseIdProvider == null ? "未注册" : "已注册");
        return built;
    }

    /** 镜像 MP 的 {@code applyConfiguration}：每工厂一个 {@link MybatisConfiguration}（MP 的 CRUD 注入挂在它上面） */
    private void applyConfiguration(MybatisSqlSessionFactoryBean factory) {
        MybatisPlusProperties.CoreConfiguration coreConfiguration = properties.getConfiguration();
        MybatisConfiguration configuration = null;
        if (coreConfiguration != null || !StringUtils.hasText(properties.getConfigLocation())) {
            configuration = new MybatisConfiguration();
        }
        if (coreConfiguration != null) {
            coreConfiguration.applyTo(configuration);
        }
        MybatisConfiguration target = configuration;
        if (target != null) {
            configurationCustomizers.orderedStream().forEach(customizer -> customizer.customize(target));
        }
        factory.setConfiguration(configuration);
    }

    /** 插件 = 容器内的链（分页 + 防全表 + 宿主 InnerInterceptor）+ 本库的跨库守卫 */
    private Interceptor[] plugins(String libraryName, DataSource dataSource) {
        Interceptor[] configured = interceptors.getIfAvailable();
        List<Interceptor> all = configured == null ? new ArrayList<>() : new ArrayList<>(List.of(configured));
        if (all.stream().noneMatch(DsCrossDbGuard.class::isInstance)) {
            all.add(DsCrossDbGuard.of(libraryName, dataSource));
        }
        return all.toArray(Interceptor[]::new);
    }

    /** 镜像 MP 的 GlobalConfig 注入，但每工厂一份副本（见类注释：{@code mapperRegistryCache} 不能共享） */
    private GlobalConfig globalConfig() {
        GlobalConfig globalConfig = properties.getGlobalConfig() == null
                ? new GlobalConfig() : copyOf(properties.getGlobalConfig());
        metaObjectHandler.ifAvailable(globalConfig::setMetaObjectHandler);
        annotationHandler.ifAvailable(globalConfig::setAnnotationHandler);
        postInitTableInfoHandler.ifAvailable(globalConfig::setPostInitTableInfoHandler);
        List<IKeyGenerator> generators = keyGenerators.orderedStream().toList();
        if (!generators.isEmpty()) {
            if (generators.size() > 1) {
                log.warn("[ds] 容器里有多个 IKeyGenerator，取第一个: {}", generators);
            }
            globalConfig.getDbConfig().setKeyGenerators(generators);
        }
        sqlInjector.ifAvailable(globalConfig::setSqlInjector);
        identifierGenerator.ifAvailable(globalConfig::setIdentifierGenerator);
        return globalConfig;
    }

    /** GlobalConfig 副本：嵌套配置也复制，注入缓存换成新实例 */
    private static GlobalConfig copyOf(GlobalConfig source) {
        GlobalConfig copy = new GlobalConfig();
        BeanUtils.copyProperties(source, copy);
        if (source.getDbConfig() != null) {
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            BeanUtils.copyProperties(source.getDbConfig(), dbConfig);
            if (source.getDbConfig().getKeyGenerators() != null) {
                dbConfig.setKeyGenerators(new ArrayList<>(source.getDbConfig().getKeyGenerators()));
            }
            copy.setDbConfig(dbConfig);
        }
        copy.setMapperRegistryCache(new HashSet<>());
        return copy;
    }

}
