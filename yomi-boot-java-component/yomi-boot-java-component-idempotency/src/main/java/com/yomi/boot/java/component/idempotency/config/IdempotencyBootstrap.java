package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.IdempotencyDataSources;
import com.yomi.boot.java.component.idempotency.stale.IdempotencyStaleCleaner;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.core.env.Environment;

/**
 * 装配收口：冻结记录库注册表，并给每个注册库配上「清理任务」。
 * <p>注册表在装配期可写（业务在自己的 {@code @Configuration} 里依赖 {@code IdempotencyDataSources} 后注册），
 * 本类在所有单例就绪后冻结它——注册必须在装配期完成，运行期不再接受变更
 * <p>清理逐库独立（每库一个集群定时任务，任务名即锁键）；记录表需在各库手工建好，组件不自动建表
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyBootstrap implements SmartInitializingSingleton {

    /** 幂等组件配置 */
    private final IdempotencyProperties properties;

    /** 记录库注册表 */
    private final IdempotencyDataSources dataSources;

    /** 用于把每库的清理器与清理任务注册成单例 Bean（装配期即完成，随容器生命周期启停） */
    private final ConfigurableListableBeanFactory beanFactory;

    /** 集群任务构造需要（键名在构造期算定） */
    private final Environment environment;

    /** 集群任务构造需要（停机等待按同类阶段超时推算） */
    private final LifecycleProperties lifecycleProperties;

    /** 冻结注册表并逐库装配清理任务 */
    @Override
    public void afterSingletonsInstantiated() {
        dataSources.freeze();
        dataSources.registrations().forEach(this::initializeLibrary);
    }

    /** 单个记录库：清理任务（开关打开时，一库一个任务） */
    private void initializeLibrary(IdempotencyDataSources.Registration registration) {
        if (!properties.isStaleCleanerEnabled()) {
            return;
        }
        String taskName = taskName(registration.name());
        IdempotencyStaleCleaner cleaner = new IdempotencyStaleCleaner(registration.repository(), properties,
                registration.name());
        beanFactory.registerSingleton("idempotencyStaleCleaner-" + registration.name(), cleaner);
        beanFactory.registerSingleton("idempotencyCleanerTask-" + registration.name(),
                new ClusterTask(taskName, properties.getScanInterval(), environment, lifecycleProperties,
                        cleaner::clean));
        log.info("[幂等:{}]记录清理已启用: taskName={}, scanInterval={}, staleTimeout={}, chunkSize={}, "
                        + "maxDeletePerRun={}, 单轮能力≈{}行/天/类",
                registration.name(), taskName, properties.getScanInterval(), properties.getStaleTimeout(),
                properties.getChunkSize(), properties.getMaxDeletePerRun(), dailyCapacity(properties));
    }

    /** 任务名即集群互斥维度（兼作锁键）：主库沿用原名，附加库带注册名后缀 */
    static String taskName(String datasourceName) {
        return IdempotencyDataSources.MASTER.equals(datasourceName)
                ? IdempotencyStaleCleaner.TASK_NAME
                : IdempotencyStaleCleaner.TASK_NAME + "-" + datasourceName;
    }

    /** 单轮上限折算的日清理能力：scanInterval 越短能力越大，用于判断保留期是否追得上写入量 */
    private static long dailyCapacity(IdempotencyProperties properties) {
        long intervalMs = Math.max(1, properties.getScanInterval().toMillis());
        return properties.getMaxDeletePerRun() * 86_400_000L / intervalMs;
    }

}
