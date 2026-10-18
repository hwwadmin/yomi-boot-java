package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.ds.support.TableInitializer;
import com.yomi.boot.java.component.event.IEventService;
import com.yomi.boot.java.component.event.consume.EventInboxGuard;
import com.yomi.boot.java.component.event.publish.EventPublisher;
import com.yomi.boot.java.component.event.publish.IEventPublisher;
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
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
 * <p>发布侧依赖：数据源、Redis、Kafka；消费侧依赖：数据源、Redis；中继与清理由集群定时任务驱动；
 * 本组件的 Mapper 由本类 {@code @MapperScan} 自行扫描，宿主无需关心依赖 jar
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@MapperScan("com.yomi.boot.java.component.event.repository.mapper")
@AutoConfigureAfter(value = {RedisAutoConfiguration.class},
        name = "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration")
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
                                         ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider) {
        properties.validate();
        if (properties.isPublisherEnabled()) {
            requirePublisherDeps(outboxMapperProvider, redisProvider, redissonProvider);
            requireKafkaTemplate(properties, applicationContext, kafkaTemplateProvider);
        }
        if (properties.isConsumerEnabled()) {
            requireConsumerDeps(inboxMapperProvider, redisProvider, redissonProvider);
        }
        warnIfRetentionRegistersNothing(properties);
        log.info("[事件]已启用 service={}, publisher={}, consumer={}",
                keys.getServiceName(), properties.isPublisherEnabled(), properties.isConsumerEnabled());
        return new EventBootstrap();
    }

    /** retention 开关打开却不会装配任何清理任务时留痕（仅消费侧启用且未开收件箱清理） */
    static void warnIfRetentionRegistersNothing(EventProperties properties) {
        EventProperties.Retention retention = properties.getRetention();
        if (retention == null || !retention.isEnabled()) {
            return;
        }
        if (properties.isConsumerEnabled() && !properties.isPublisherEnabled()
                && !retention.isInboxRetentionEnabled()) {
            log.warn("[事件]yomi.event.retention.enabled=true 但仅消费侧启用且 "
                    + "yomi.event.retention.inbox-retention-enabled=false：不会装配任何清理任务"
                    + "（发件箱清理随 publisher 生效，收件箱清理需再开 inbox-retention-enabled）");
        }
    }

    /**
     * 事务模板：容器里已有（Boot 标准 Bean 或宿主自定义）即按 {@code @ConditionalOnMissingBean} 原样复用，
     * 都没有才注册默认 REQUIRED 模板。消费侧 inbox 插入与业务写入必须同事务，所以只认应用的事务管理器：
     * 多个管理器无 {@code @Primary} 时启动失败，报错点名候选。
     * <p>类上的 {@code @AutoConfigureAfter(Boot TransactionAutoConfiguration)} 保证注册顺序：缺它时按类名字母序，
     * 组件会先于 Boot 注册，占用容器级 {@code transactionTemplate} 这个 Bean 名。
     */
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
        public OutboxDispatcher outboxDispatcher(EventProperties properties,
                                                 IEventOutboxRepository outboxRepository,
                                                 IEventOutboxDlqRepository dlqRepository,
                                                 TransactionTemplate transactionTemplate) {
            return new OutboxDispatcher(properties, outboxRepository, dlqRepository, transactionTemplate);
        }

        @Bean
        @ConditionalOnMissingBean
        public OutboxRelayCoordinator outboxRelayCoordinator(EventProperties properties,
                                                             EventKeys keys,
                                                             IEventOutboxRepository outboxRepository,
                                                             IEventOutboxDlqRepository dlqRepository,
                                                             OutboxDispatcher dispatcher) {
            return new OutboxRelayCoordinator(properties, keys, outboxRepository, dlqRepository, dispatcher);
        }

        /** 抓取发件箱 PENDING 记录并投递 MQ（集群定时任务：同一时刻只有一个实例执行） */
        @Bean
        public ClusterTask eventOutboxFetchTask(EventProperties properties, OutboxRelayCoordinator coordinator,
                                               Environment environment) {
            return new ClusterTask(OutboxRelayCoordinator.TASK_FETCH, properties.getFetchInterval(), environment,
                    coordinator::fetchAndDispatch);
        }

        /** 回收长时间停留在 FETCHED 的发件箱记录（重置为 PENDING） */
        @Bean
        public ClusterTask eventOutboxFetchTimeoutTask(EventProperties properties,
                                                       OutboxRelayCoordinator coordinator,
                                                       Environment environment) {
            return new ClusterTask(OutboxRelayCoordinator.TASK_FETCH_TIMEOUT, properties.getFetchInterval(),
                    environment, coordinator::recoverFetchedTimeout);
        }

        /** 重试发件箱死信（DLQ），清理已成功或无主的死信行 */
        @Bean
        public ClusterTask eventDlqRetryTask(EventProperties properties, OutboxRelayCoordinator coordinator,
                                            Environment environment) {
            return new ClusterTask(OutboxRelayCoordinator.TASK_DLQ, properties.getDlqScanInterval(), environment,
                    coordinator::processDlq);
        }

        /** 保留期清理（默认关闭）：仅删 SENT_SUCCESS 且超过保留期的行 */
        @Bean
        @ConditionalOnProperty(value = "yomi.event.retention.enabled", havingValue = "true")
        public EventRetentionCleaner eventOutboxRetentionCleaner(EventProperties properties, EventKeys keys,
                                                                 IEventOutboxRepository outboxRepository) {
            log.info("[事件]发件箱保留期清理已启用: outboxRetention={}, scanInterval={}",
                    properties.getRetention().getOutboxRetention(), properties.getRetention().getScanInterval());
            return new EventRetentionCleaner(properties, keys, outboxRepository, null);
        }

        /** 发件箱保留期清理任务 */
        @Bean
        @ConditionalOnProperty(value = "yomi.event.retention.enabled", havingValue = "true")
        public ClusterTask eventOutboxRetentionTask(EventProperties properties,
                                                    @Qualifier("eventOutboxRetentionCleaner")
                                                    EventRetentionCleaner cleaner,
                                                    Environment environment) {
            return new ClusterTask(EventRetentionCleaner.TASK_NAME_OUTBOX,
                    properties.getRetention().getScanInterval(), environment, cleaner::cleanOutbox);
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
         * 保留期短于这个窗口时，超期重投的事件会被当作新消息再消费一次</p>
         */
        @Bean
        @ConditionalOnProperty(value = "yomi.event.retention.enabled", havingValue = "true")
        @ConditionalOnProperty(value = "yomi.event.retention.inbox-retention-enabled", havingValue = "true")
        public EventRetentionCleaner eventInboxRetentionCleaner(EventProperties properties,
                                                                EventKeys keys,
                                                                IEventInboxRepository inboxRepository) {
            log.info("[事件]收件箱保留期清理已启用: inboxRetention={}, scanInterval={}",
                    properties.getRetention().getInboxRetention(), properties.getRetention().getScanInterval());
            return new EventRetentionCleaner(properties, keys, null, inboxRepository);
        }

        /** 收件箱保留期清理任务 */
        @Bean
        @ConditionalOnProperty(value = "yomi.event.retention.enabled", havingValue = "true")
        @ConditionalOnProperty(value = "yomi.event.retention.inbox-retention-enabled", havingValue = "true")
        public ClusterTask eventInboxRetentionTask(EventProperties properties,
                                                   @Qualifier("eventInboxRetentionCleaner")
                                                   EventRetentionCleaner cleaner,
                                                   Environment environment) {
            return new ClusterTask(EventRetentionCleaner.TASK_NAME_INBOX,
                    properties.getRetention().getScanInterval(), environment, cleaner::cleanInbox);
        }
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
            throw new IllegalStateException("yomi.event.publisher-enabled=true 需要 Redis/Redisson（中继任务的集群互斥锁）");
        }
    }

    /**
     * 发布侧 KafkaTemplate 启动期校验：第一条消息发送前就确定用哪个模板，
     * 配错或候选不唯一时启动失败，报错给出候选列表与对应配置键：
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

}
