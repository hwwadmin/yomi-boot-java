package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.ds.support.TableInitializer;
import com.yomi.boot.java.component.idempotency.annotation.IdempotentAop;
import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import com.yomi.boot.java.component.idempotency.repository.IdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper;
import com.yomi.boot.java.component.idempotency.stale.IdempotencyStaleCleaner;
import com.yomi.boot.java.component.idempotency.store.IdempotencyKeys;
import com.yomi.boot.java.component.idempotency.store.IdempotencyRedisStore;
import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 幂等组件自动装配；需 {@code yomi.idempotency.enabled=true}
 * <p>
 * 依赖：Redis/Redisson、数据源与事务、IdempotencyRecordMapper（resources/mapper/IdempotencyRecordMapper.xml）
 * <p>本组件的 Mapper 由本类 {@code @MapperScan} 自行扫描，宿主无需关心依赖 jar
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@MapperScan("com.yomi.boot.java.component.idempotency.repository.mapper")
@AutoConfigureAfter({RedisAutoConfiguration.class})
@EnableConfigurationProperties(IdempotencyProperties.class)
@ConditionalOnProperty(value = "yomi.idempotency.enabled", havingValue = "true")
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyKeys idempotencyKeys(IdempotencyProperties properties, Environment environment) {
        return new IdempotencyKeys(resolvePrefix(properties, environment));
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyRedisStore idempotencyRedisStore(StringRedisTemplate stringRedisTemplate,
                                                       IdempotencyKeys keys,
                                                       IdempotencyProperties properties) {
        return new IdempotencyRedisStore(stringRedisTemplate, keys, properties);
    }

    /**
     * 事务模板解析：容器里有（宿主自定义或 Boot 标准 {@code transactionTemplate}）就直接复用、保留宿主语义——
     * 占位、业务与写回同一事务，用应用的事务管理器；没有则按事务管理器自建默认 REQUIRED 私有模板（不注册为
     * Bean，不占用 Boot 标准 Bean 名）；模板/管理器多候选且无 {@code @Primary} 时启动失败并点名候选，不随意挑一个
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyGuard idempotencyGuard(IIdempotencyRecordRepository repository,
                                             IdempotencyRedisStore redisStore,
                                             IdempotencyKeys keys,
                                             IdempotencyProperties properties,
                                             ObjectProvider<TransactionTemplate> transactionTemplateProvider,
                                             ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        TransactionTemplate transactionTemplate = transactionTemplateProvider.getIfAvailable();
        if (transactionTemplate == null) {
            PlatformTransactionManager manager = transactionManagerProvider.getIfAvailable();
            if (manager == null) {
                throw new IllegalStateException(
                        "yomi.idempotency.enabled=true 需要事务管理器：请配置数据源并启用 JDBC");
            }
            transactionTemplate = new TransactionTemplate(manager);
        }
        return new IdempotencyGuard(repository, redisStore, keys, transactionTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentAop idempotentAop(IdempotencyGuard guard) {
        return new IdempotentAop(guard);
    }

    @Bean
    @ConditionalOnMissingBean(IIdempotencyRecordRepository.class)
    public IIdempotencyRecordRepository idempotencyRecordRepository(IdempotencyRecordMapper mapper) {
        return new IdempotencyRecordRepository(mapper);
    }

    /** 记录清理（默认关闭，需 yomi.idempotency.stale-cleaner-enabled=true） */
    @Bean
    @ConditionalOnProperty(value = "yomi.idempotency.stale-cleaner-enabled", havingValue = "true")
    public IdempotencyStaleCleaner idempotencyStaleCleaner(IIdempotencyRecordRepository repository,
                                                           IdempotencyProperties properties) {
        log.info("[幂等]记录清理已启用: scanInterval={}, staleTimeout={}, batchSize={}",
                properties.getScanInterval(), properties.getStaleTimeout(), properties.getBatchSize());
        return new IdempotencyStaleCleaner(repository, properties);
    }

    /** 记录清理任务（集群定时任务：同一时刻只有一个实例执行） */
    @Bean
    @ConditionalOnProperty(value = "yomi.idempotency.stale-cleaner-enabled", havingValue = "true")
    public ClusterTask idempotencyRecordCleanerTask(IdempotencyProperties properties,
                                                    IdempotencyStaleCleaner cleaner,
                                                    Environment environment) {
        return new ClusterTask(IdempotencyStaleCleaner.TASK_NAME, properties.getScanInterval(), environment,
                cleaner::clean);
    }

    /** 启动期校验依赖并打日志 */
    @Bean
    public IdempotencyBootstrap idempotencyBootstrap(IdempotencyProperties properties,
                                                     IdempotencyKeys keys,
                                                     ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                     ObjectProvider<RedissonClient> redissonClientProvider,
                                                     ObjectProvider<IdempotencyRecordMapper> mapperProvider) {
        properties.validate();
        if (redisTemplateProvider.getIfAvailable() == null || redissonClientProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.idempotency.enabled=true 需要 Redis/Redisson：请配置 spring.data.redis.host");
        }
        if (mapperProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.idempotency.enabled=true 需要 IdempotencyRecordMapper：请执行组件 jar 内 "
                            + "sql/mysql/sys_idempotency_record.sql 或 sql/pgsql/sys_idempotency_record.sql；"
                            + "Mapper 由 component-idempotency 自动配置扫描，无需宿主配置 @MapperScan");
        }
        log.info("[幂等]守卫已启用: storePrefix={}, resultCacheTtl={}, recordRetention={}, staleCleanerEnabled={}",
                keys.getPrefix(), properties.getResultCacheTtl(), properties.getRecordRetention(),
                properties.isStaleCleanerEnabled());
        return new IdempotencyBootstrap();
    }

    /** 启动期建表：缺表时按当前数据库方言执行 sql/mysql|pgsql/sys_idempotency_record.sql 自动创建 */
    @Bean
    public TableInitializer idempotencyTableInitializer(ObjectProvider<DataSource> dataSourceProvider) {
        return new TableInitializer(dataSourceProvider, "幂等", "sys_idempotency_record.sql",
                List.of("sys_idempotency_record"));
    }

    /** 从环境补全存储键前缀 */
    private static String resolvePrefix(IdempotencyProperties properties, Environment environment) {
        String raw = properties.getStorePrefix();
        if (StringUtils.isBlank(raw)) {
            String applicationName = environment.getProperty("spring.application.name");
            if (StringUtils.isBlank(applicationName)) {
                throw new IllegalStateException(
                        "yomi.idempotency.store-prefix 未配置，且无法从 spring.application.name 推导，请显式配置 store-prefix");
            }
            raw = "yomi:idempotency:" + applicationName;
        }
        return raw;
    }

    /** 无业务逻辑，仅触发上述校验 */
    public static final class IdempotencyBootstrap {
    }

}
