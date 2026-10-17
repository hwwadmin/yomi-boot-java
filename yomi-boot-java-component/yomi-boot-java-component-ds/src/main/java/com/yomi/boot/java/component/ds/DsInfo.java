package com.yomi.boot.java.component.ds;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据源信息展示
 * <p>
 * 启动时输出容器里所有数据源（含业务自建的附加库）的 Bean 名、连接目标、类型与连接池类型，便于确认生效配置；
 * 类型取自 JDBC url 的子协议，不建立连接；用于选择方言语句的标识由 {@code DsDatabaseIdProvider} 按活动
 * 连接的产品名解析并单独打印一条日志
 *
 * @author zwei
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DsInfo {

    /** 容器内全部 {@link DataSource} Bean（Bean 名 → 实例） */
    private final Map<String, DataSource> dataSourceBeans;

    /** 启动后打印各数据源连接目标、类型、用户名与连接池类型 */
    @PostConstruct
    public void init() {
        if (dataSourceBeans == null || dataSourceBeans.isEmpty()) {
            log.info("数据源配置未启用.");
            return;
        }
        new TreeMap<>(dataSourceBeans).forEach((name, dataSource) -> {
            log.info("数据源连接信息 [{}: {} - {}]，类型: {}", name, connectionTarget(dataSource),
                    username(dataSource), jdbcType(dataSource));
            log.info("数据源连接池类型: {}", dataSource.getClass().getName());
        });
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
