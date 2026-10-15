package com.yomi.boot.java.component.ds;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据源预热
 * <p>
 * 并行预热所有真实数据源：单个数据源受单池超时约束，整体受总预算约束，
 * 启动耗时上限为总预算；预热失败或超时只记录 ERROR 日志，不阻断应用启动。
 * 可通过 yomi.boot.ds.preheat.enabled=false 关闭预热（默认开启）
 *
 * @author zwei
 */
@Component
@ConditionalOnProperty(name = "yomi.boot.ds.preheat.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DsPreheat {

    /** 单个数据源预热超时（毫秒），兜底连接池自身未配置合理 connectionTimeout 的场景 */
    private static final long SINGLE_TIMEOUT_MILLIS = 10_000L;

    /** 单连接探活超时（秒），传给 {@link Connection#isValid(int)}；四目标库通用 */
    private static final int VALIDATE_TIMEOUT_SECONDS = 2;

    /** 预热总预算（毫秒），并行执行下应不小于单池超时；超时后放弃等待，剩余预热在后台继续 */
    private static final long TOTAL_BUDGET_MILLIS = 15_000L;

    /** 容器内全部 {@link DataSource} Bean */
    private final List<DataSource> dataSourceList;

    /** 启动后并行探活各真实连接池（失败不阻断启动） */
    @PostConstruct
    public void preheat() {
        List<PreheatTarget> targets = resolvePreheatTargets(dataSourceList);
        if (CollectionUtils.isEmpty(targets)) {
            return;
        }
        preheatTargets(targets);
    }

    /**
     * 并行预热：单个数据源失败或超时不影响其他数据源
     */
    private void preheatTargets(List<PreheatTarget> targets) {
        long start = System.nanoTime();
        try (ShutdownOnCloseExecutor executor = new ShutdownOnCloseExecutor()) {
            AtomicInteger failed = new AtomicInteger();
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(target -> CompletableFuture
                            .<Boolean>supplyAsync(() -> preheat(target), executor)
                            // 单池硬超时：到点不再等（池自身 connectionTimeout 可能更长），预热线程在后台跑完即止
                            .orTimeout(SINGLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            .<Void>handle((success, ex) -> {
                                if (Objects.nonNull(ex)) {
                                    failed.incrementAndGet();
                                    logPreheatAbnormal(target, ex);
                                } else if (!success) {
                                    failed.incrementAndGet();
                                }
                                return null;
                            }))
                    .toList();
            awaitPreheat(futures, targets, failed, start);
        }
    }

    /**
     * 等待预热完成；超过总预算时放弃等待并输出未完成的数据源，不阻断启动
     */
    private void awaitPreheat(List<CompletableFuture<Void>> futures, List<PreheatTarget> targets,
                              AtomicInteger failed, long start) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TOTAL_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            log.info("数据库连接池预热完成，共 {} 个数据源，失败 {} 个，耗时 {} 毫秒.",
                    targets.size(), failed.get(), (System.nanoTime() - start) / 1_000_000);
        } catch (TimeoutException e) {
            log.error("数据库连接池预热总预算 {} 毫秒超时，放弃等待，未完成数据源: {}，应用继续启动.",
                    TOTAL_BUDGET_MILLIS, collectUnfinished(futures, targets));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("数据库连接池预热被中断", e);
        } catch (ExecutionException e) {
            log.error("数据库连接池预热异常", e);
        }
    }

    /** 收集未完成的预热 Future */
    private static List<String> collectUnfinished(List<CompletableFuture<Void>> futures, List<PreheatTarget> targets) {
        List<String> unfinished = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            if (!futures.get(i).isDone()) {
                unfinished.add(targets.get(i).name());
            }
        }
        return unfinished;
    }

    /**
     * 单池预热失败时记录 ERROR 日志，不阻断应用启动
     */
    private static void logPreheatAbnormal(PreheatTarget target, Throwable ex) {
        Throwable cause = ex instanceof CompletionException && Objects.nonNull(ex.getCause()) ? ex.getCause() : ex;
        if (cause instanceof TimeoutException) {
            log.error("数据源[{}]预热超时（超过 {} 毫秒），应用继续启动.", target.name(), SINGLE_TIMEOUT_MILLIS);
        } else {
            log.error("数据源[{}]预热异常，应用继续启动.", target.name(), cause);
        }
    }

    /**
     * 解析需要预热的真实数据源：展开 dynamic-datasource 路由内的子池，并按实例去重
     */
    static List<PreheatTarget> resolvePreheatTargets(List<DataSource> dataSourceList) {
        if (CollectionUtils.isEmpty(dataSourceList)) {
            return List.of();
        }
        Map<DataSource, String> unique = new IdentityHashMap<>();
        for (DataSource dataSource : dataSourceList) {
            if (dataSource instanceof DynamicRoutingDataSource routing) {
                Map<String, DataSource> children = routing.getDataSources();
                if (children != null) {
                    children.forEach((name, ds) -> unique.putIfAbsent(ds, name));
                }
                continue;
            }
            unique.putIfAbsent(dataSource, resolveDataSourceName(dataSource));
        }
        return unique.entrySet().stream()
                .map(entry -> new PreheatTarget(entry.getValue(), entry.getKey()))
                .toList();
    }

    /** 解析数据源名称（日志展示） */
    private static String resolveDataSourceName(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getPoolName();
        }
        return dataSource.getClass().getSimpleName();
    }

    /**
     * 数据源预热
     * <p>用 JDBC 标准探活 {@link Connection#isValid(int)}：MySQL/PG/Oracle/达梦 通用。
     * 不用 {@code SELECT 1}——Oracle（及达梦 Oracle 兼容模式）不接受无 {@code FROM} 的 SELECT</p>
     *
     * @param target 预热目标
     * @return 是否预热成功
     */
    private boolean preheat(PreheatTarget target) {
        try (Connection conn = target.dataSource().getConnection()) {
            return conn.isValid(VALIDATE_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.error("数据源[{}]预热失败，应用继续启动.", target.name(), e);
            return false;
        }
    }

    /**
     * 仅 shutdown，不等待任务结束：总预算超时后剩余预热可在后台继续，不阻塞启动。
     */
    private record ShutdownOnCloseExecutor(ExecutorService delegate) implements Executor, AutoCloseable {

        ShutdownOnCloseExecutor() {
            this(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ds-preheat-", 0).factory()));
        }

        /** 将预热任务提交到虚拟线程池 */
        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        /** 仅 shutdown，不等待未完成任务（配合总预算超时） */
        @Override
        public void close() {
            delegate.shutdown();
        }
    }

    /**
     * 预热目标
     *
     * @param name       数据源名称（dynamic-datasource 的数据源名或连接池名）
     * @param dataSource 数据源
     */
    record PreheatTarget(String name, DataSource dataSource) {
    }

}
