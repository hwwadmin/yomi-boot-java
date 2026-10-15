package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.ds.support.TableInitializer;
import com.yomi.boot.java.component.event.IEventService;
import com.yomi.boot.java.component.event.consume.EventInboxGuard;
import com.yomi.boot.java.component.event.publish.EventPublisher;
import com.yomi.boot.java.component.event.publish.IEventPublisher;
import com.yomi.boot.java.component.event.relay.EventOutboxDlqAdmin;
import com.yomi.boot.java.component.event.relay.OutboxDispatcher;
import com.yomi.boot.java.component.event.relay.OutboxRelayCoordinator;
import com.yomi.boot.java.component.event.repository.EventInboxRepository;
import com.yomi.boot.java.component.event.repository.IEventInboxRepository;
import com.yomi.boot.java.component.event.repository.EventOutboxDlqRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxDlqRepository;
import com.yomi.boot.java.component.event.repository.EventOutboxRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxRepository;
import com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxDlqMapper;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper;
import com.yomi.boot.java.component.event.retention.EventRetentionCleaner;
import com.yomi.boot.java.component.event.service.EventService;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.roundrobin.RoundRobin;
import com.yomi.boot.java.component.roundrobin.annotation.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.config.RoundRobinAutoConfiguration;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinDefaults;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 发件箱/收件箱事件组件自动装配
 * <p>发布侧依赖：数据源、Redis、RoundRobin、Kafka；消费侧依赖：数据源、Redis；
 * 本组件的 Mapper 由本类 {@code @MapperScan} 自行扫描，宿主无需关心依赖 jar
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@MapperScan("com.yomi.boot.java.component.event.repository.mapper")
@AutoConfigureAfter({RedisAutoConfiguration.class, RoundRobinAutoConfiguration.class})
@EnableConfigurationProperties(EventProperties.class)
@ConditionalOnExpression("'${yomi.event.publisher-enabled:false}' == 'true' or '${yomi.event.consumer-enabled:false}' == 'true'")
public class EventAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventKeys eventKeys(EventProperties properties, Environment environment) {
        return new EventKeys(resolveServiceName(properties, environment));
    }

    /** 事件查询与运维服务（业务接入）：未启用的能力调用时给出带配置指引的异常 */
    @Bean
    @ConditionalOnMissingBean(IEventService.class)
    public IEventService eventService(ObjectProvider<IEventOutboxRepository> outboxProvider,
                                      ObjectProvider<IEventOutboxDlqRepository> dlqProvider,
                                      ObjectProvider<IEventInboxRepository> inboxProvider,
                                      EventKeys eventKeys,
                                      TransactionTemplate transactionTemplate) {
        return new EventService(outboxProvider, dlqProvider, inboxProvider, eventKeys, transactionTemplate);
    }

    /** 启动期校验依赖并打日志 */
    @Bean
    public EventBootstrap eventBootstrap(EventProperties properties,
                                         EventKeys keys,
                                         ApplicationContext applicationContext,
                                         ObjectProvider<EventOutboxMapper> outboxMapperProvider,
                                         ObjectProvider<EventInboxMapper> inboxMapperProvider,
                                         ObjectProvider<StringRedisTemplate> redisProvider,
                                         ObjectProvider<RedissonClient> redissonProvider,
                                         ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider,
                                         ObjectProvider<RoundRobinRegistry> roundRobinRegistryProvider) {
        properties.validate();
        // 轮询调度是否真的可用：以容器里有没有 RoundRobinRegistry 为准，
        // 不读 yomi.roundrobin.enabled（该键只认字面量 true，'1'/'yes' 会让属性判定通过而任务静默不注册）
        boolean roundRobinReady = roundRobinRegistryProvider.getIfAvailable() != null;
        if (properties.isPublisherEnabled()) {
            requireRoundRobin(roundRobinReady, "yomi.event.publisher-enabled=true");
            requirePublisherDeps(outboxMapperProvider, redisProvider, redissonProvider);
            requireKafkaTemplate(properties, applicationContext, kafkaTemplateProvider);
        }
        if (properties.isConsumerEnabled()) {
            requireConsumerDeps(inboxMapperProvider, redisProvider, redissonProvider);
        }
        requireRoundRobinForRetention(properties, roundRobinReady);
        log.info("[事件]已启用 service={}, publisher={}, consumer={}",
                keys.getServiceName(), properties.isPublisherEnabled(), properties.isConsumerEnabled());
        return new EventBootstrap();
    }

    /** 依赖轮询调度的开关：RoundRobinRegistry 不在容器里即启动失败（不读属性，防"真值但非 true"静默） */
    static void requireRoundRobin(boolean roundRobinReady, String switchedOn) {
        if (roundRobinReady) {
            return;
        }
        throw new IllegalStateException(switchedOn + " 需要 yomi.roundrobin.enabled=true"
                + "（RoundRobinRegistry 未注册：投递中继、DLQ 重试与保留期清理都由轮询任务调度；"
                + "该键只认字面量 true，'1'/'yes' 不生效）");
    }

    /**
     * 保留期清理的依赖校验：发布侧已被 {@link #requireRoundRobin} 挡住，
     * 消费侧是"开关开启但任务被 roundrobin 条件静默过滤"的剩余缺口，这里把它变响亮
     */
    static void requireRoundRobinForRetention(EventProperties properties, boolean roundRobinReady) {
        EventProperties.Retention retention = properties.getRetention();
        if (retention == null || !retention.isEnabled()) {
            return;
        }
        if (properties.isConsumerEnabled() && retention.isInboxRetentionEnabled() && !roundRobinReady) {
            throw new IllegalStateException("yomi.event.retention.inbox-retention-enabled=true 需要 "
                    + "yomi.roundrobin.enabled=true（RoundRobinRegistry 未注册）：清理任务由轮询调度，"
                    + "否则开关看似开启、收件箱清理永不执行");
        }
        if (properties.isConsumerEnabled() && !properties.isPublisherEnabled()
                && !retention.isInboxRetentionEnabled()) {
            log.warn("[事件]yomi.event.retention.enabled=true 但仅消费侧启用且 "
                    + "yomi.event.retention.inbox-retention-enabled=false：不会注册任何清理任务"
                    + "（发件箱清理随 publisher 生效，收件箱清理需再开 inbox-retention-enabled）");
        }
    }

    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate eventTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** 发布端：`yomi.event.publisher-enabled=true` 时装配 */
    @Configuration
    @ConditionalOnProperty(value = "yomi.event.publisher-enabled", havingValue = "true")
    static class PublisherConfiguration {

        /** 启动期建表：缺表时按当前数据库方言执行 sql/mysql|pgsql/sys_event.sql 自动创建 */
        @Bean
        public TableInitializer eventOutboxTableInitializer(ObjectProvider<DataSource> dataSourceProvider) {
            return new TableInitializer(dataSourceProvider, "事件发件箱", "sys_event.sql",
                    List.of("sys_event_outbox", "sys_event_outbox_dlq"));
        }

        @Bean
        @ConditionalOnMissingBean(IEventOutboxRepository.class)
        public IEventOutboxRepository eventOutboxRepository(EventOutboxMapper mapper) {
            return new EventOutboxRepository(mapper);
        }

        @Bean
        @ConditionalOnMissingBean(IEventOutboxDlqRepository.class)
        public IEventOutboxDlqRepository eventOutboxDlqRepository(EventOutboxDlqMapper mapper) {
            return new EventOutboxDlqRepository(mapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public IEventPublisher eventPublisher(IEventOutboxRepository repository, EventKeys keys,
                                            EventProperties properties) {
            return new EventPublisher(repository, keys, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public EventOutboxDlqAdmin eventOutboxDlqAdmin(IEventOutboxDlqRepository dlqRepository, EventKeys keys) {
            return new EventOutboxDlqAdmin(dlqRepository, keys);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(RoundRobinTask.class)
        @ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
        public OutboxDispatcher outboxDispatcher(EventProperties properties,
                                                 IEventOutboxRepository outboxRepository,
                                                 IEventOutboxDlqRepository dlqRepository,
                                                 TransactionTemplate transactionTemplate) {
            return new OutboxDispatcher(properties, outboxRepository, dlqRepository, transactionTemplate);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(RoundRobinTask.class)
        @ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
        public OutboxRelayCoordinator outboxRelayCoordinator(EventProperties properties,
                                                             EventKeys keys,
                                                             IEventOutboxRepository outboxRepository,
                                                             IEventOutboxDlqRepository dlqRepository,
                                                             OutboxDispatcher dispatcher) {
            return new OutboxRelayCoordinator(properties, keys, outboxRepository, dlqRepository, dispatcher);
        }

        @Bean
        @ConditionalOnClass(RoundRobinTask.class)
        @ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
        public EventOutboxRoundRobinRegistrar eventOutboxRoundRobinRegistrar(EventProperties properties,
                                                                             EventKeys keys,
                                                                             OutboxRelayCoordinator coordinator,
                                                                             RoundRobinRegistry registry) {
            registerRelayTasks(registry, properties, keys, coordinator);
            return new EventOutboxRoundRobinRegistrar();
        }

        /** 保留期清理（默认关闭）：仅删 SENT_SUCCESS 且超过保留期的行 */
        @Bean
        @ConditionalOnClass(RoundRobinTask.class)
        @ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
        @ConditionalOnProperty(value = "yomi.event.retention.enabled", havingValue = "true")
        public EventRetentionCleaner eventOutboxRetentionCleaner(EventProperties properties,
                                                                 EventKeys keys,
                                                                 IEventOutboxRepository outboxRepository,
                                                                 RoundRobinRegistry registry) {
            EventRetentionCleaner cleaner = new EventRetentionCleaner(properties, keys, outboxRepository, null);
            registerRetentionTask(registry, properties, EventRetentionCleaner.TASK_NAME_OUTBOX,
                    "清理已成功发送且超过保留期的发件箱行", cleaner::cleanOutbox);
            log.info("[事件]发件箱保留期清理已注册: outboxRetention={}, scanInterval={}",
                    properties.getRetention().getOutboxRetention(), properties.getRetention().getScanInterval());
            return cleaner;
        }
    }

    /** 消费端：`yomi.event.consumer-enabled=true` 时装配 */
    @Configuration
    @ConditionalOnProperty(value = "yomi.event.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        /** 启动期建表：缺表时按当前数据库方言执行 sql/mysql|pgsql/sys_event.sql 自动创建 */
        @Bean
        public TableInitializer eventInboxTableInitializer(ObjectProvider<DataSource> dataSourceProvider) {
            return new TableInitializer(dataSourceProvider, "事件收件箱", "sys_event.sql",
                    List.of("sys_event_inbox"));
        }

        @Bean
        @ConditionalOnMissingBean(IEventInboxRepository.class)
        public IEventInboxRepository eventInboxRepository(EventInboxMapper mapper) {
            return new EventInboxRepository(mapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public EventInboxGuard eventInboxGuard(IEventInboxRepository repository,
                                               EventKeys keys,
                                               EventProperties properties,
                                               TransactionTemplate transactionTemplate) {
            return new EventInboxGuard(repository, keys, properties, transactionTemplate);
        }

        /**
         * 收件箱保留期清理（默认关闭，需额外开启 inbox-retention-enabled）。
         * <p>收件箱行是"只消费一次"的依据：保留期必须 ≥ 事件可能被重投的最长时间（Kafka topic retention），
         * 否则超期重投的事件会被当作新消息再消费一次</p>
         */
        @Bean
        @ConditionalOnClass(RoundRobinTask.class)
        @ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
        @ConditionalOnProperty(value = "yomi.event.retention.enabled", havingValue = "true")
        @ConditionalOnProperty(value = "yomi.event.retention.inbox-retention-enabled", havingValue = "true")
        public EventRetentionCleaner eventInboxRetentionCleaner(EventProperties properties,
                                                                EventKeys keys,
                                                                IEventInboxRepository inboxRepository,
                                                                RoundRobinRegistry registry) {
            EventRetentionCleaner cleaner = new EventRetentionCleaner(properties, keys, null, inboxRepository);
            registerRetentionTask(registry, properties, EventRetentionCleaner.TASK_NAME_INBOX,
                    "清理超过保留期的收件箱行（保留期须 ≥ 事件可能重投的最长时间）", cleaner::cleanInbox);
            log.info("[事件]收件箱保留期清理已注册: inboxRetention={}, scanInterval={}",
                    properties.getRetention().getInboxRetention(), properties.getRetention().getScanInterval());
            return cleaner;
        }
    }

    /** 注册发件箱 RoundRobin 后台任务 */
    private static void registerRelayTasks(RoundRobinRegistry registry,
                                           EventProperties properties,
                                           EventKeys keys,
                                           OutboxRelayCoordinator coordinator) {
        RoundRobinDefaults defaults = RoundRobinDefaults.builder().build();
        registry.register(RoundRobin.task(
                RoundRobinConfig.simple(keys.roundRobinName(OutboxRelayCoordinator.TASK_FETCH),
                        "抓取发件箱 PENDING 记录并投递 MQ",
                        properties.getFetchInterval(), defaults),
                coordinator::fetchAndDispatch));
        registry.register(RoundRobin.task(
                RoundRobinConfig.simple(keys.roundRobinName(OutboxRelayCoordinator.TASK_FETCH_TIMEOUT),
                        "回收长时间停留在 FETCHED 的发件箱记录（重置为 PENDING）",
                        properties.getFetchInterval(), defaults),
                coordinator::recoverFetchedTimeout));
        registry.register(RoundRobin.task(
                RoundRobinConfig.simple(keys.roundRobinName(OutboxRelayCoordinator.TASK_DLQ),
                        "重试发件箱死信（DLQ），清理已成功或无主的死信行",
                        properties.getDlqScanInterval(), defaults),
                coordinator::processDlq));
        log.info("[事件]发件箱轮询任务已注册 fetchInterval={}, dlqInterval={}",
                properties.getFetchInterval(), properties.getDlqScanInterval());
    }

    /** 注册保留期清理任务（间隔取 yomi.event.retention.scan-interval） */
    private static void registerRetentionTask(RoundRobinRegistry registry, EventProperties properties,
                                              String taskName, String description, RoundRobinHandler handler) {
        RoundRobinDefaults defaults = RoundRobinDefaults.builder().build();
        RoundRobinConfig config = RoundRobinConfig.simple(
                taskName, description, properties.getRetention().getScanInterval(), defaults);
        config.validate();
        registry.register(RoundRobin.task(config, handler));
    }

    /** 校验发布侧 Mapper 等依赖 */
    private static void requirePublisherDeps(ObjectProvider<EventOutboxMapper> outboxMapperProvider,
                                             ObjectProvider<StringRedisTemplate> redisProvider,
                                             ObjectProvider<RedissonClient> redissonProvider) {
        if (outboxMapperProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.event.publisher-enabled=true 需要 EventOutboxMapper：请执行组件 jar 内 "
                            + "sql/mysql/sys_event.sql 或 sql/pgsql/sys_event.sql；"
                            + "Mapper 由 component-event 自动配置扫描，无需宿主配置 @MapperScan");
        }
        if (redisProvider.getIfAvailable() == null || redissonProvider.getIfAvailable() == null) {
            throw new IllegalStateException("yomi.event.publisher-enabled=true 需要 Redis/Redisson（roundrobin 分布式锁）");
        }
    }

    /**
     * 发布侧 KafkaTemplate 启动期强校验（原实现只查"存在"，配了 bean 名不校验、多候选时还会抛容器原始歧义异常）；
     * 校验放到启动期，避免"第一条消息发送才失败"：
     * <ul>
     *     <li>配了 {@code yomi.event.kafka-template-bean-name} → 该名称必须存在且是 KafkaTemplate；</li>
     *     <li>未配 → 必须能唯一确定（唯一候选或 @Primary），多候选无 @Primary 直接启动失败</li>
     * </ul>
     */
    static void requireKafkaTemplate(EventProperties properties, ApplicationContext context,
                                     ObjectProvider<KafkaTemplate<String, Object>> templateProvider) {
        String beanName = properties.getKafkaTemplateBeanName();
        if (StringUtils.isNotBlank(beanName)) {
            if (!context.containsBean(beanName) || !context.isTypeMatch(beanName, KafkaTemplate.class)) {
                throw new IllegalStateException("yomi.event.kafka-template-bean-name=" + beanName
                        + " 未找到对应的 KafkaTemplate Bean；现有候选: " + kafkaTemplateCandidates(context));
            }
            return;
        }
        Map<String, KafkaTemplate> candidates = context.getBeansOfType(KafkaTemplate.class);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("yomi.event.publisher-enabled=true 需要 KafkaTemplate：请配置 spring.kafka");
        }
        if (candidates.size() > 1 && templateProvider.getIfUnique() == null) {
            throw new IllegalStateException("存在多个 KafkaTemplate Bean " + candidates.keySet()
                    + " 且没有 @Primary：请配置 yomi.event.kafka-template-bean-name 指定使用哪个，"
                    + "或给其中一个 Bean 加 @Primary");
        }
    }

    /** 列举容器内 KafkaTemplate 候选名 */
    private static String kafkaTemplateCandidates(ApplicationContext context) {
        return java.util.Arrays.toString(context.getBeanNamesForType(KafkaTemplate.class));
    }

    /** 校验消费侧 Mapper 等依赖 */
    private static void requireConsumerDeps(ObjectProvider<EventInboxMapper> inboxMapperProvider,
                                            ObjectProvider<StringRedisTemplate> redisProvider,
                                            ObjectProvider<RedissonClient> redissonProvider) {
        if (inboxMapperProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.event.consumer-enabled=true 需要 EventInboxMapper：请执行组件 jar 内 "
                            + "sql/mysql/sys_event.sql 或 sql/pgsql/sys_event.sql；"
                            + "Mapper 由 component-event 自动配置扫描，无需宿主配置 @MapperScan");
        }
        if (redisProvider.getIfAvailable() == null || redissonProvider.getIfAvailable() == null) {
            throw new IllegalStateException("yomi.event.consumer-enabled=true 需要 Redis/Redisson");
        }
    }

    static String resolveServiceName(EventProperties properties, Environment environment) {
        if (StringUtils.isNotBlank(properties.getServiceName())) {
            return properties.getServiceName().trim();
        }
        String app = environment.getProperty("spring.application.name");
        if (StringUtils.isBlank(app)) {
            throw new IllegalStateException(
                    "yomi.event.service-name 未配置且无法从 spring.application.name 推导");
        }
        return app.trim();
    }

    /** 无业务逻辑，仅触发启动校验 */
    public static final class EventBootstrap {
    }

    /** 无业务逻辑，仅触发 RoundRobin 任务注册 */
    public static final class EventOutboxRoundRobinRegistrar {
    }

}
