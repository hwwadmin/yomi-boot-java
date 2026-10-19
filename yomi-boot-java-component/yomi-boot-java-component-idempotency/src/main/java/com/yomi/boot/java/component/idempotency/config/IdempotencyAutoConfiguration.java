package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.idempotency.IdempotencyDataSources;
import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.IdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper;
import com.yomi.boot.java.component.idempotency.store.IdempotencyKeys;
import com.yomi.boot.java.component.idempotency.store.IdempotencyRedisStore;
import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;

/**
 * 幂等组件自动装配；需 {@code yomi.idempotency.enabled=true}
 * <p>
 * 依赖：Redis/Redisson、主数据源（component-ds 装配，主库的 Mapper 锚在主库）、
 * IdempotencyRecordMapper（resources/mapper/IdempotencyRecordMapper.xml）
 * <p>不清求也不注册事务基础设施：事务边界由调用方给（{@code @Transactional} 或显式传入的
 * {@code TransactionTemplate}），组件只在自己的调用里沿用或使用它
 * <p>默认只注册主库（自动清理）；附加库要放幂等记录，业务在自己的 {@code @Configuration} 里
 * 依赖 {@link IdempotencyDataSources} 后自行注册（注册即同样获得自动清理能力）
 * <p>记录表需手工建好（仓库根目录 {@code sql/mysql|pgsql/sys_idempotency_record.sql}），组件不自动建表
 * <p>本组件的 Mapper 由本类 {@code @MapperScan} 自行扫描（不写 ref，按类型落主库），宿主无需关心依赖 jar
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
    public IdempotencyDataSources idempotencyDataSources() {
        return new IdempotencyDataSources();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyKeys idempotencyKeys(IdempotencyProperties properties, Environment environment) {
        return new IdempotencyKeys(resolvePrefix(properties, environment));
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyRedisStore idempotencyRedisStore(StringRedisTemplate stringRedisTemplate,
                                                       IdempotencyProperties properties) {
        return new IdempotencyRedisStore(stringRedisTemplate, properties);
    }

    /**
     * 幂等守卫：记录库按本次事务解析（默认只有主库），事务边界由调用方给
     * （{@code @Transactional} 或 {@code IdempotencyUtils} 的 template 入参）——组件不建、也不注入事务设施
     */
    @Bean
    @ConditionalOnMissingBean
    // 校验先行：依赖项缺失时要报组件自己的错，而不是 Spring 的注入错误
    @DependsOn("idempotencyBootstrap")
    public IdempotencyGuard idempotencyGuard(IdempotencyDataSources dataSources,
                                             IdempotencyKeys keys,
                                             IdempotencyRedisStore redisStore,
                                             IdempotencyProperties properties) {
        return new IdempotencyGuard(dataSources, keys, redisStore, properties);
    }

    /** 主库的默认记录仓储（宿主可声明自己的实现覆盖）；附加库的仓储在注册时按各自工厂建 */
    @Bean
    @ConditionalOnMissingBean(IIdempotencyRecordRepository.class)
    public IIdempotencyRecordRepository idempotencyRecordRepository(IdempotencyRecordMapper mapper) {
        return new IdempotencyRecordRepository(mapper);
    }

    /** 启动期校验依赖、注册主库并冻结注册表；附加库由业务在装配期自行注册 */
    @Bean
    public IdempotencyBootstrap idempotencyBootstrap(IdempotencyProperties properties,
                                                     IdempotencyDataSources dataSources,
                                                     IIdempotencyRecordRepository repository,
                                                     ConfigurableListableBeanFactory beanFactory,
                                                     Environment environment,
                                                     ObjectProvider<LifecycleProperties> lifecycleProperties,
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
                    "yomi.idempotency.enabled=true 需要 IdempotencyRecordMapper：请执行仓库根目录 "
                            + "sql/mysql/sys_idempotency_record.sql 或 sql/pgsql/sys_idempotency_record.sql；"
                            + "Mapper 由 component-idempotency 自动配置扫描，无需宿主配置 @MapperScan");
        }
        DataSource master = DsMaster.requireDataSource(beanFactory);
        dataSources.register(IdempotencyDataSources.MASTER, master, repository);
        log.info("[幂等]守卫已启用: storePrefix={}, resultCacheTtl={}, recordRetention={}, staleCleanerEnabled={}",
                resolvePrefix(properties, environment), properties.getResultCacheTtl(), properties.getRecordRetention(),
                properties.isStaleCleanerEnabled());
        return new IdempotencyBootstrap(properties, dataSources, beanFactory, environment,
                lifecycleProperties.getIfAvailable(LifecycleProperties::new));
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

}
