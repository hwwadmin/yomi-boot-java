package com.yomi.boot.java.component.rbac.config;

import com.yomi.boot.java.component.ds.support.TableInitializer;
import com.yomi.boot.java.component.rbac.IRbacAdminService;
import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.cache.RbacProjectionKeys;
import com.yomi.boot.java.component.rbac.cache.RbacProjectionStore;
import com.yomi.boot.java.component.rbac.service.RbacAdminService;
import com.yomi.boot.java.component.rbac.service.RbacAuthService;
import com.yomi.boot.java.component.rbac.relay.RbacProjectionRefresher;
import com.yomi.boot.java.component.rbac.repository.*;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacAuthMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacGroupPermRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermGroupMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleGroupRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacUserRoleRelMapper;
import com.yomi.boot.java.component.rbac.web.RbacExceptionAdvice;
import com.yomi.boot.java.component.rbac.web.RbacInterceptor;
import com.yomi.boot.java.component.rbac.web.RbacWebMvcConfig;
import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.config.TokenAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * RBAC 自动装配；需 {@code yomi.rbac.enabled=true}
 * <p>本组件的 Mapper 由本类 {@code @MapperScan} 自行扫描，宿主无需关心依赖 jar
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@MapperScan("com.yomi.boot.java.component.rbac.repository.mapper")
@AutoConfigureAfter({TokenAutoConfiguration.class, RedisAutoConfiguration.class})
@EnableConfigurationProperties(RbacProperties.class)
@ConditionalOnProperty(value = "yomi.rbac.enabled", havingValue = "true")
public class RbacAutoConfiguration {

    // ---- 仓储（端口）：数据访问统一入口；Mapper 只在此注入，宿主可实现端口并注册自己的 Bean ----

    @Bean
    @ConditionalOnMissingBean(IRbacPermRepository.class)
    public RbacPermRepository rbacPermRepository(ObjectProvider<RbacPermMapper> mapperProvider) {
        return new RbacPermRepository(requireMapper(mapperProvider, "RbacPermMapper"));
    }

    @Bean
    @ConditionalOnMissingBean(IRbacPermGroupRepository.class)
    public RbacPermGroupRepository rbacPermGroupRepository(ObjectProvider<RbacPermGroupMapper> mapperProvider) {
        return new RbacPermGroupRepository(requireMapper(mapperProvider, "RbacPermGroupMapper"));
    }

    @Bean
    @ConditionalOnMissingBean(IRbacRoleRepository.class)
    public RbacRoleRepository rbacRoleRepository(ObjectProvider<RbacRoleMapper> mapperProvider) {
        return new RbacRoleRepository(requireMapper(mapperProvider, "RbacRoleMapper"));
    }

    @Bean
    @ConditionalOnMissingBean(IRbacAuthRepository.class)
    public RbacAuthRepository rbacAuthRepository(ObjectProvider<RbacAuthMapper> mapperProvider) {
        return new RbacAuthRepository(requireMapper(mapperProvider, "RbacAuthMapper"));
    }

    @Bean
    @ConditionalOnMissingBean(IRbacRelationRepository.class)
    public RbacRelationRepository rbacRelationRepository(ObjectProvider<RbacUserRoleRelMapper> userRoleRelMapperProvider,
                                                        ObjectProvider<RbacRoleGroupRelMapper> roleGroupRelMapperProvider,
                                                        ObjectProvider<RbacGroupPermRelMapper> groupPermRelMapperProvider,
                                                        IRbacRoleRepository roleRepository) {
        return new RbacRelationRepository(
                requireMapper(userRoleRelMapperProvider, "RbacUserRoleRelMapper"),
                requireMapper(roleGroupRelMapperProvider, "RbacRoleGroupRelMapper"),
                requireMapper(groupPermRelMapperProvider, "RbacGroupPermRelMapper"),
                roleRepository);
    }

    @Bean
    @ConditionalOnMissingBean(IRbacSnapshotRepository.class)
    public RbacSnapshotRepository rbacSnapshotRepository(ObjectProvider<RbacPermMapper> permMapperProvider,
                                                        ObjectProvider<RbacPermGroupMapper> permGroupMapperProvider,
                                                        ObjectProvider<RbacRoleMapper> roleMapperProvider,
                                                        ObjectProvider<RbacUserRoleRelMapper> userRoleRelMapperProvider,
                                                        ObjectProvider<RbacRoleGroupRelMapper> roleGroupRelMapperProvider,
                                                        ObjectProvider<RbacGroupPermRelMapper> groupPermRelMapperProvider) {
        return new RbacSnapshotRepository(
                requireMapper(permMapperProvider, "RbacPermMapper"),
                requireMapper(permGroupMapperProvider, "RbacPermGroupMapper"),
                requireMapper(roleMapperProvider, "RbacRoleMapper"),
                requireMapper(userRoleRelMapperProvider, "RbacUserRoleRelMapper"),
                requireMapper(roleGroupRelMapperProvider, "RbacRoleGroupRelMapper"),
                requireMapper(groupPermRelMapperProvider, "RbacGroupPermRelMapper"));
    }

    /** 取必需的 Mapper：缺失时给出与建表脚本、Mapper 自动扫描一致的指引 */
    private static <T> T requireMapper(ObjectProvider<T> mapperProvider, String mapperName) {
        T mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException("yomi.rbac.enabled=true 需要 " + mapperName
                    + "：请执行组件 jar 内 sql/mysql/sys_rbac.sql 或 sql/pgsql/sys_rbac.sql（含内置超管种子）；"
                    + "Mapper 由 component-rbac 自动配置扫描，无需宿主配置 @MapperScan");
        }
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public RbacProjectionKeys rbacProjectionKeys(RbacProperties properties, Environment environment) {
        return new RbacProjectionKeys(resolvePrefix(properties, environment));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = "yomi.rbac.projection-enabled", havingValue = "true", matchIfMissing = true)
    public RbacProjectionStore rbacProjectionStore(StringRedisTemplate stringRedisTemplate,
                                                   RbacProjectionKeys keys,
                                                   RbacProperties properties,
                                                   IRbacSnapshotRepository snapshotRepository) {
        return new RbacProjectionStore(stringRedisTemplate, keys, properties, snapshotRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public IRbacAuthService rbacAuthService(IRbacAuthRepository authRepository,
                                           RbacProperties properties,
                                           ObjectProvider<RbacProjectionStore> projectionStoreProvider) {
        RbacProjectionStore store = properties.isProjectionEnabled()
                ? projectionStoreProvider.getIfAvailable() : null;
        return new RbacAuthService(authRepository, properties, store);
    }

    @Bean
    @ConditionalOnMissingBean
    public IRbacAdminService rbacAdminService(IRbacPermRepository permRepository,
                                             IRbacPermGroupRepository permGroupRepository,
                                             IRbacRoleRepository roleRepository,
                                             IRbacRelationRepository relationRepository,
                                             IRbacAuthRepository authRepository) {
        return new RbacAdminService(permRepository, permGroupRepository, roleRepository,
                relationRepository, authRepository);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public RbacInterceptor rbacInterceptor(ITokenService tokenService,
                                           IRbacAuthService rbacAuthService,
                                           RbacProperties properties) {
        return new RbacInterceptor(tokenService, rbacAuthService, properties);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public RbacWebMvcConfig rbacWebMvcConfig(RbacInterceptor rbacInterceptor, RbacProperties properties) {
        return new RbacWebMvcConfig(rbacInterceptor, properties);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public RbacExceptionAdvice rbacExceptionAdvice(ObjectProvider<ITokenService> tokenServiceProvider) {
        return new RbacExceptionAdvice(tokenServiceProvider);
    }

    /** 定时投影刷新（默认关闭，需 yomi.rbac.scheduled-refresh-enabled=true） */
    @Bean
    @ConditionalOnProperty(value = "yomi.rbac.scheduled-refresh-enabled", havingValue = "true")
    @ConditionalOnProperty(value = "yomi.rbac.projection-enabled", havingValue = "true", matchIfMissing = true)
    public RbacProjectionRefresher rbacProjectionRefresher(IRbacAuthService authService, RbacProperties properties) {
        log.info("[RBAC]定时投影刷新已启用: interval={}", properties.getScheduledRefreshInterval());
        return new RbacProjectionRefresher(authService);
    }

    /** 投影刷新任务（集群定时任务：同一时刻只有一个实例执行） */
    @Bean
    @ConditionalOnProperty(value = "yomi.rbac.scheduled-refresh-enabled", havingValue = "true")
    @ConditionalOnProperty(value = "yomi.rbac.projection-enabled", havingValue = "true", matchIfMissing = true)
    public ClusterTask rbacProjectionRefreshTask(RbacProperties properties, RbacProjectionRefresher refresher,
                                                 Environment environment,
                                                 LifecycleProperties lifecycleProperties) {
        return new ClusterTask(RbacProjectionRefresher.TASK_NAME,
                properties.getScheduledRefreshInterval(), environment, lifecycleProperties, refresher::refresh);
    }

    @Bean
    public ApplicationRunner rbacBootstrapRefresh(RbacProperties properties, IRbacAuthService authService) {
        return args -> {
            if (!properties.isBootstrapRefresh() || !properties.isProjectionEnabled()) {
                return;
            }
            try {
                authService.refreshProjection();
                log.info("[RBAC] 启动期鉴权投影已刷新");
            } catch (Exception e) {
                log.warn("[RBAC] 启动期刷新投影失败，冷启动可能全拒绝直至手动 refresh", e);
            }
        };
    }

    @Bean
    public RbacBootstrap rbacBootstrap(RbacProperties properties,
                                       RbacProjectionKeys keys,
                                       ObjectProvider<StringRedisTemplate> redisProvider,
                                       ObjectProvider<RbacAuthMapper> authMapperProvider) {
        properties.validate();
        if (properties.isProjectionEnabled() && redisProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.rbac.projection-enabled=true 需要 Redis：请配置 spring.data.redis.host");
        }
        if (authMapperProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "yomi.rbac.enabled=true 需要 RbacAuthMapper：请执行组件 jar 内 "
                            + "sql/mysql/sys_rbac.sql 或 sql/pgsql/sys_rbac.sql（含内置超管种子）；"
                            + "Mapper 由 component-rbac 自动配置扫描，无需宿主配置 @MapperScan");
        }
        log.info("[RBAC]已启用: storePrefix={}, projectionEnabled={}, httpPathStyle={}",
                keys.getPrefix(), properties.isProjectionEnabled(), properties.getHttpPathStyle());
        return new RbacBootstrap();
    }

    /** 启动期建表（主库）：缺表时按当前数据库方言执行 sql/mysql|pgsql/sys_rbac.sql 自动创建
     *（含内置超管种子），建表后请求可正常通过鉴权 */
    @Bean
    public TableInitializer rbacTableInitializer(ListableBeanFactory beanFactory) {
        return TableInitializer.onMaster(beanFactory, "RBAC", "sys_rbac.sql", List.of(
                "sys_rbac_perm", "sys_rbac_perm_group", "sys_rbac_role",
                "sys_rbac_user_role_rel", "sys_rbac_role_group_rel", "sys_rbac_group_perm_rel"));
    }

    /** 从环境补全存储键前缀 */
    private static String resolvePrefix(RbacProperties properties, Environment environment) {
        String raw = properties.getStorePrefix();
        if (StringUtils.isBlank(raw)) {
            String applicationName = environment.getProperty("spring.application.name");
            if (StringUtils.isBlank(applicationName)) {
                throw new IllegalStateException(
                        "yomi.rbac.store-prefix 未配置，且无法从 spring.application.name 推导，请显式配置 store-prefix");
            }
            raw = "yomi:rbac:" + applicationName;
        }
        return raw;
    }

    /** 占位 Bean：触发 RBAC 启动校验与日志 */
    public static final class RbacBootstrap {
    }

}
