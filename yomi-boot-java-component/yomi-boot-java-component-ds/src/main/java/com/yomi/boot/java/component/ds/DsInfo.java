package com.yomi.boot.java.component.ds;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据源信息展示
 * <p>
 * 启动时输出各数据源的连接目标、类型与连接池类型，便于确认实际生效的数据源配置；
 * 与 {@link DsPreheat} 一致，dynamic-datasource 场景展开为真实子数据源；
 * 类型取自 JDBC url 的子协议（零连接开销）；真正用于选择方言语句的标识由
 * {@code DsDatabaseIdProvider} 另打一条日志（它按活连接的产品名解析）
 *
 * @author zwei
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DsInfo {

    /** 容器内全部 {@link DataSource} Bean（含 dynamic-datasource 路由） */
    private final List<DataSource> dataSourceList;

    /** 启动后打印各数据源连接目标、类型、用户名与连接池类型 */
    @PostConstruct
    public void init() {
        Map<String, DataSource> dataSources = resolveDataSources(dataSourceList);
        if (dataSources.isEmpty()) {
            log.info("数据源配置未启用.");
            return;
        }
        dataSources.forEach((name, dataSource) -> {
            DataSource realDataSource = unwrapDataSource(dataSource);
            log.info("数据源连接信息 [{}: {} - {}]，类型: {}", name, connectionTarget(realDataSource),
                    username(realDataSource), jdbcType(realDataSource));
            log.info("数据源连接池类型: {}", realDataSource.getClass().getName());
        });
    }

    /**
     * 解析真实数据源：dynamic-datasource 展开为子数据源，按名称排序保证输出顺序稳定
     */
    private static Map<String, DataSource> resolveDataSources(List<DataSource> dataSourceList) {
        Map<String, DataSource> dataSources = new TreeMap<>();
        if (CollectionUtils.isEmpty(dataSourceList)) {
            return dataSources;
        }
        for (DataSource dataSource : dataSourceList) {
            if (dataSource instanceof DynamicRoutingDataSource routing) {
                Map<String, DataSource> children = routing.getDataSources();
                if (children != null) {
                    dataSources.putAll(children);
                }
                continue;
            }
            dataSources.put(resolveDataSourceName(dataSource), dataSource);
        }
        return dataSources;
    }

    /** 解析数据源名称（日志展示） */
    private static String resolveDataSourceName(DataSource dataSource) {
        DataSource realDataSource = unwrapDataSource(dataSource);
        if (realDataSource instanceof HikariDataSource hikari) {
            return hikari.getPoolName();
        }
        return realDataSource.getClass().getSimpleName();
    }

    /**
     * 真实连接池：dynamic-datasource 以 ItemDataSource 包装实际数据源，展示时展开到包装内的连接池
     */
    private static DataSource unwrapDataSource(DataSource dataSource) {
        if (dataSource instanceof ItemDataSource itemDataSource && itemDataSource.getRealDataSource() != null) {
            return itemDataSource.getRealDataSource();
        }
        return dataSource;
    }

    /**
     * 连接目标：去掉驱动前缀与连接参数，仅保留 host:port/database；
     * 非 url 形式（如 jdbc:h2:mem:test）原样输出，取值不可用时以 ~ 占位
     */
    private static String connectionTarget(DataSource dataSource) {
        String jdbcUrl = dataSource instanceof HikariDataSource hikari ? hikari.getJdbcUrl() : null;
        if (StringUtils.isBlank(jdbcUrl)) {
            return "~";
        }
        String target = StringUtils.substringAfter(jdbcUrl, "://");
        if (StringUtils.isBlank(target)) {
            return jdbcUrl;
        }
        return StringUtils.substringBefore(target, "?");
    }

    /** 从数据源解析用户名（日志） */
    private static String username(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && StringUtils.isNotBlank(hikari.getUsername())) {
            return hikari.getUsername();
        }
        return "~";
    }

    /**
     * 数据源类型：取 JDBC url 的子协议（{@code jdbc:mysql://x} → {@code mysql}、{@code jdbc:postgresql://x}
     * → {@code postgresql}、{@code jdbc:h2:mem:t} → {@code h2}）；取值不可用时以 ~ 占位
     */
    private static String jdbcType(DataSource dataSource) {
        String jdbcUrl = dataSource instanceof HikariDataSource hikari ? hikari.getJdbcUrl() : null;
        if (StringUtils.isBlank(jdbcUrl)) {
            return "~";
        }
        String rest = StringUtils.substringAfter(jdbcUrl, "jdbc:");
        String type = StringUtils.substringBefore(rest, ":");
        return StringUtils.isBlank(type) || type.equals(rest) ? "~" : type;
    }

}
