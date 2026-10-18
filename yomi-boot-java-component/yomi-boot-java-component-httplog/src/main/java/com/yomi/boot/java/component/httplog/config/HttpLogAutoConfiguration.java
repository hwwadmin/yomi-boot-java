package com.yomi.boot.java.component.httplog.config;

import com.yomi.boot.java.component.desensitize.IDesensitizeService;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategyRegistry;
import com.yomi.boot.java.component.desensitize.config.DesensitizeAutoConfiguration;
import com.yomi.boot.java.component.ds.support.TableInitializer;
import com.yomi.boot.java.component.httplog.IHttpLogService;
import com.yomi.boot.java.component.httplog.collect.HttpLogContextEnricher;
import com.yomi.boot.java.component.httplog.collect.HttpLogFilter;
import com.yomi.boot.java.component.httplog.collect.HttpLogRecordFactory;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.queue.kafka.KafkaHttpLogTransport;
import com.yomi.boot.java.component.httplog.queue.memory.MemoryHttpLogTransport;
import com.yomi.boot.java.component.httplog.queue.redis.RedisHttpLogTransport;
import com.yomi.boot.java.component.httplog.repository.HttpLogRepository;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.repository.mapper.HttpLogMapper;
import com.yomi.boot.java.component.httplog.retention.HttpLogRetentionCleaner;
import com.yomi.boot.java.component.httplog.service.HttpLogService;
import com.yomi.boot.java.component.httplog.store.DefaultHttpLogStore;
import com.yomi.boot.java.component.httplog.store.HttpLogStore;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import com.yomi.boot.java.component.kafka.config.KafkaAutoConfiguration;
import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.redis.config.RedisConfiguredCondition;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 请求日志组件自动装配
 * <p>采集全量（请求/响应头、请求/响应体，默认开）+ 可插拔传输（memory/redis/kafka）+ 共享批量落库；
 * 脱敏编排对接 component-desensitize（词表全配置，零内置词表）；保留期清理由集群定时任务（Redis 锁互斥）驱动；
 * 本组件的 Mapper 由本类 {@code @MapperScan} 自行扫描，宿主无需关心依赖 jar
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@MapperScan("com.yomi.boot.java.component.httplog.repository.mapper")
@AutoConfigureAfter(
        value = {
                RedisAutoConfiguration.class,
                DesensitizeAutoConfiguration.class,
                KafkaAutoConfiguration.class},
        // Boot 4 已模块化（类名不复用 auto-configure 包），按名引用，无需编译期依赖
        name = {
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
                "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"})
@EnableConfigurationProperties(HttpLogProperties.class)
@ConditionalOnProperty(value = "yomi.httplog.enabled", havingValue = "true")
public class HttpLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HttpLogKeys httpLogKeys(HttpLogProperties properties, Environment environment) {
        return new HttpLogKeys(properties, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLogMetrics httpLogMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new HttpLogMetrics(meterRegistryProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLogDesensitizer httpLogDesensitizer(IDesensitizeService desensitizeService,
                                                   HttpLogProperties properties) {
        return new HttpLogDesensitizer(desensitizeService, properties.getDesensitize());
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLogRecordFactory httpLogRecordFactory(HttpLogProperties properties, HttpLogKeys keys,
                                                     HttpLogDesensitizer desensitizer,
                                                     ObjectProvider<HttpLogContextEnricher> enrichers) {
        return new HttpLogRecordFactory(properties, keys, desensitizer, enrichers.orderedStream().toList());
    }

    /** 仓储（端口）：持久化唯一出入口；Mapper 仅注入此处，宿主自行实现 IHttpLogRepository 并注册 Bean 后以宿主的实现为准 */
    @Bean
    @ConditionalOnMissingBean(IHttpLogRepository.class)
    public IHttpLogRepository httpLogRepository(HttpLogMapper httpLogMapper,
                                                ObjectProvider<SqlSessionFactory> sqlSessionFactoryProvider) {
        SqlSessionFactory sqlSessionFactory = sqlSessionFactoryProvider.getIfAvailable();
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("yomi.httplog 需要 SqlSessionFactory：请引入 component-ds；"
                    + "Mapper 由 component-httplog 自动配置扫描，无需宿主配置 @MapperScan");
        }
        return new HttpLogRepository(httpLogMapper, sqlSessionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLogStore httpLogStore(HttpLogProperties properties, IHttpLogRepository httpLogRepository,
                                     HttpLogMetrics metrics) {
        return new DefaultHttpLogStore(properties, httpLogRepository, metrics);
    }

    /** 查询服务（业务接入）：宿主自行实现 IHttpLogService 并注册 Bean 后以宿主的实现为准 */
    @Bean
    @ConditionalOnMissingBean(IHttpLogService.class)
    public IHttpLogService httpLogService(IHttpLogRepository httpLogRepository, HttpLogKeys httpLogKeys) {
        return new HttpLogService(httpLogRepository, httpLogKeys);
    }

    /** 启动期建表：缺表时按当前数据库方言执行 sql/mysql|pgsql/sys_http_log.sql 自动创建 */
    @Bean
    public TableInitializer httpLogTableInitializer(ObjectProvider<DataSource> dataSourceProvider) {
        return new TableInitializer(dataSourceProvider, "HTTP请求日志", "sys_http_log.sql",
                List.of("sys_http_log"));
    }

    @Bean
    public HttpLogLifecycle httpLogLifecycle(HttpLogProperties properties, HttpLogSink httpLogSink,
                                             HttpLogConsumer httpLogConsumer, HttpLogStore httpLogStore) {
        return new HttpLogLifecycle(properties, httpLogSink, httpLogConsumer, httpLogStore);
    }

    /** 启动自检 + 生效摘要：配置错误直接抛异常，配置缺失 WARN 放行 */
    @Bean
    public HttpLogBootstrap httpLogBootstrap(HttpLogProperties properties, HttpLogKeys keys,
                                             HttpLogDesensitizer desensitizer,
                                             ObjectProvider<DesensitizeStrategyRegistry> registryProvider,
                                             ObjectProvider<StringRedisTemplate> redisProvider,
                                             ObjectProvider<ConsumerFactory<?, ?>> consumerFactoryProvider,
                                             ApplicationContext context,
                                             Environment environment) {
        properties.validate();
        validateDesensitize(properties, registryProvider.getIfAvailable());
        validateMode(properties, context, redisProvider, consumerFactoryProvider);
        warnRetentionWithoutRedis(properties, environment);
        logSummary(properties, keys, desensitizer);
        return new HttpLogBootstrap();
    }

    /** 脱敏校验：空词表 WARN 放行；引用未注册策略名直接抛异常 */
    private static void validateDesensitize(HttpLogProperties properties, DesensitizeStrategyRegistry registry) {
        HttpLogProperties.Desensitize desensitize = properties.getDesensitize();
        if (!desensitize.isEnabled()) {
            log.warn("[httplog]脱敏已关闭（yomi.httplog.desensitize.enabled=false），请求/响应敏感数据将原样入库");
            return;
        }
        Map<String, String> keyTypes = desensitize.getKeyTypes();
        if (keyTypes.isEmpty()) {
            log.warn("[httplog]脱敏已启用但未配置任何键名（yomi.httplog.desensitize.key-types 为空），"
                    + "请求/响应敏感数据将原样入库；配置示例: key-types: { authorization: ALL, password: PASSWORD, "
                    + "mobile: MOBILE }（策略名见 component-desensitize）");
            return;
        }
        if (registry == null) {
            throw new IllegalStateException(
                    "yomi.httplog.desensitize 需要 DesensitizeStrategyRegistry（component-desensitize 自动装配）");
        }
        Set<String> registered = registry.names();
        List<String> missing = keyTypes.values().stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .filter(name -> !registered.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("yomi.httplog.desensitize.key-types 引用了未注册的脱敏策略: " + missing
                    + "（已注册: " + registered + "）；自定义策略请先注册 DesensitizeStrategy Bean");
        }
    }

    /** 模式依赖校验：缺依赖直接抛异常 */
    private static void validateMode(HttpLogProperties properties, ApplicationContext context,
                                     ObjectProvider<StringRedisTemplate> redisProvider,
                                     ObjectProvider<ConsumerFactory<?, ?>> consumerFactoryProvider) {
        switch (properties.getMode()) {
            case MEMORY -> {
            }
            case REDIS -> {
                if (redisProvider.getIfAvailable() == null) {
                    throw new IllegalStateException(
                            "yomi.httplog.mode=redis 需要 Redis：请配置 spring.data.redis.* 并引入 component-redis");
                }
            }
            case KAFKA -> {
                requireKafkaTemplate(properties, context);
                if (consumerFactoryProvider.getIfAvailable() == null) {
                    throw new IllegalStateException("yomi.httplog.mode=kafka 需要 ConsumerFactory：请配置 spring.kafka.*");
                }
            }
        }
    }

    /** 多 KafkaTemplate 时的启动期校验 */
    private static void requireKafkaTemplate(HttpLogProperties properties, ApplicationContext context) {
        String beanName = properties.getKafka().getTemplateBeanName();
        if (StringUtils.isNotBlank(beanName)) {
            if (!context.containsBean(beanName) || !context.isTypeMatch(beanName, KafkaTemplate.class)) {
                throw new IllegalStateException("yomi.httplog.kafka.template-bean-name=" + beanName
                        + " 未找到对应的 KafkaTemplate Bean；现有候选: "
                        + Arrays.toString(context.getBeanNamesForType(KafkaTemplate.class)));
            }
            return;
        }
        Map<String, KafkaTemplate> candidates = context.getBeansOfType(KafkaTemplate.class);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("yomi.httplog.mode=kafka 需要 KafkaTemplate：请配置 spring.kafka");
        }
        if (context.getBeanProvider(KafkaTemplate.class).getIfUnique() == null) {
            throw new IllegalStateException("存在多个 KafkaTemplate Bean " + candidates.keySet()
                    + " 且没有 @Primary：请配置 yomi.httplog.kafka.template-bean-name，或给其中一个 Bean 加 @Primary");
        }
    }

    /** 启用保留清理但未配置 Redis 时打告警（清理任务用 Redis 锁做集群互斥，未配置 Redis 则不装配） */
    private static void warnRetentionWithoutRedis(HttpLogProperties properties, Environment environment) {
        if (properties.getRetention().isEnabled() && !RedisConfiguredCondition.isConfigured(environment)) {
            log.warn("[httplog]保留期清理已启用但未配置 Redis（spring.data.redis.*）：清理任务不会装配，"
                    + "sys_http_log 将只增不减（配置 yomi.httplog.retention.enabled=false 可消除本告警）");
        }
    }

    /** 启动时输出队列/存储等装配摘要 */
    private static void logSummary(HttpLogProperties properties, HttpLogKeys keys, HttpLogDesensitizer desensitizer) {
        HttpLogProperties.Collect collect = properties.getCollect();
        HttpLogProperties.Retention retention = properties.getRetention();
        log.info("[httplog]已启用 service={}, mode={}, 采集[请求头={},请求体={},响应头={},响应体={}], "
                        + "截断[请求={}B,响应={}B], 缓冲[容量={},批量={}], 脱敏[键={},body={}], 保留={}",
                keys.getServiceName(), properties.getMode(),
                collect.isRecordRequestHeaders(), collect.isRecordRequestBody(),
                collect.isRecordResponseHeaders(), collect.isRecordResponseBody(),
                collect.getMaxRequestBodyBytes(), collect.getMaxResponseBodyBytes(),
                properties.getBuffer().getCapacity(), properties.getBuffer().getBatchSize(),
                desensitizer.active() ? properties.getDesensitize().getKeyTypes().size() : 0,
                desensitizer.bodyEnabled(), retention.isEnabled() ? retention.getDays() + "天" : "关闭");
        log.info("[httplog]磁盘提示: 全量采集下行宽约含头/体（上限 {}B+{}B），磁盘 ≈ QPS × 行宽 × 86400 × 保留天数，上线前请评估",
                collect.getMaxRequestBodyBytes(), collect.getMaxResponseBodyBytes());
    }

    /** 仅触发启动校验，无业务逻辑 */
    public static final class HttpLogBootstrap {
    }

    /** mode=memory（默认）：进程内有界缓冲直落库 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(value = "yomi.httplog.mode", havingValue = "memory", matchIfMissing = true)
    static class MemoryConfiguration {

        @Bean
        public MemoryHttpLogTransport memoryHttpLogTransport(HttpLogProperties properties, HttpLogMetrics metrics,
                                                             HttpLogDesensitizer desensitizer) {
            return new MemoryHttpLogTransport(properties, metrics, desensitizer);
        }

    }

    /** mode=redis：List 结构（默认）或 Stream 结构中转 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(value = "yomi.httplog.mode", havingValue = "redis")
    static class RedisConfiguration {

        @Bean
        public RedisHttpLogTransport redisHttpLogTransport(HttpLogProperties properties, HttpLogKeys keys,
                                                           ObjectProvider<StringRedisTemplate> redisProvider,
                                                           HttpLogDesensitizer desensitizer,
                                                           HttpLogMetrics metrics) {
            StringRedisTemplate redisTemplate = redisProvider.getIfAvailable();
            if (redisTemplate == null) {
                throw new IllegalStateException(
                        "yomi.httplog.mode=redis 需要 Redis：请配置 spring.data.redis.* 并引入 component-redis");
            }
            return new RedisHttpLogTransport(properties, keys, redisTemplate, desensitizer, metrics);
        }

    }

    /** mode=kafka：多服务日志汇聚管道 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(value = "yomi.httplog.mode", havingValue = "kafka")
    static class KafkaConfiguration {

        @Bean
        public KafkaHttpLogTransport kafkaHttpLogTransport(HttpLogProperties properties, HttpLogKeys keys,
                                                           ObjectProvider<ConsumerFactory<?, ?>> consumerFactoryProvider,
                                                           HttpLogDesensitizer desensitizer,
                                                           HttpLogMetrics metrics) {
            ConsumerFactory<?, ?> consumerFactory = consumerFactoryProvider.getIfAvailable();
            if (consumerFactory == null) {
                throw new IllegalStateException("yomi.httplog.mode=kafka 需要 ConsumerFactory：请配置 spring.kafka.*");
            }
            return new KafkaHttpLogTransport(properties, keys, consumerFactory, desensitizer, metrics);
        }

    }

    /** 采集过滤器（仅 Servlet 应用注册；WebFlux 只装配传输与落库） */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public HttpLogFilter httpLogFilter(HttpLogProperties properties, HttpLogRecordFactory factory,
                                           HttpLogSink httpLogSink, HttpLogMetrics metrics) {
            return new HttpLogFilter(properties, factory, httpLogSink, metrics);
        }

        @Bean
        public FilterRegistrationBean<HttpLogFilter> httpLogFilterRegistration(HttpLogFilter filter,
                                                                              HttpLogProperties properties) {
            FilterRegistrationBean<HttpLogFilter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/*");
            registration.setOrder(properties.getCollect().getFilterOrder());
            registration.setName("httpLogFilter");
            return registration;
        }

    }

    /** 保留期清理（默认开；需要 Redis 做集群互斥，未配置 Redis 时不装配） */
    @Configuration(proxyBeanMethods = false)
    @Conditional(RedisConfiguredCondition.class)
    static class RetentionConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(value = "yomi.httplog.retention.enabled", havingValue = "true", matchIfMissing = true)
        public HttpLogRetentionCleaner httpLogRetentionCleaner(HttpLogProperties properties, HttpLogKeys keys,
                                                               IHttpLogRepository httpLogRepository) {
            log.info("[httplog]保留期清理已启用: days={}, scanInterval={}",
                    properties.getRetention().getDays(), properties.getRetention().getScanInterval());
            return new HttpLogRetentionCleaner(properties, keys, httpLogRepository);
        }

        /** 保留期清理任务（集群定时任务：同一时刻只有一个实例执行） */
        @Bean
        @ConditionalOnProperty(value = "yomi.httplog.retention.enabled", havingValue = "true", matchIfMissing = true)
        public ClusterTask httpLogRetentionTask(HttpLogProperties properties, HttpLogRetentionCleaner cleaner,
                                               Environment environment,
                                               LifecycleProperties lifecycleProperties) {
            return new ClusterTask(HttpLogRetentionCleaner.TASK_NAME,
                    properties.getRetention().getScanInterval(), environment, lifecycleProperties, cleaner::clean);
        }

    }

}
