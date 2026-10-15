package com.yomi.boot.java.component.ds.support;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动期初始化组件依赖的数据表：逐表探活，缺表时按当前数据库方言执行组件内置建表脚本自动建表。
 * <p>
 * 建表脚本随组件 jar 提供（{@code sql/mysql/xxx.sql} 与 {@code sql/pgsql/xxx.sql}），按 JDBC 连接的
 * {@code getMetaData().getDatabaseProductName()} 选方言，目前支持 MySQL 与 PostgreSQL；
 * 脚本内建表语句幂等（{@code CREATE TABLE IF NOT EXISTS}），故缺任意一张表即整脚本执行一次
 * （可能顺带创建尚无需要的兄弟表，属预期）。表已齐全时不做任何 DDL。
 * <p>
 * 脚本缺失、执行失败、方言不支持或建表后仍缺表，一律阻止启动（fail-fast），错误点名缺失的表与脚本路径。
 * 探活用 {@code SELECT 1 FROM t WHERE 1 = 0}，只验证表可访问、不读取数据行；
 * 在所有单例就绪后执行一次（早于 Web 对外服务）。
 *
 * @author zwei
 */
@Slf4j
public class TableInitializer implements SmartInitializingSingleton {

    /** 建表脚本在 jar 内的目录前缀（末级目录为方言名） */
    private static final String SQL_DIR = "sql/";

    /** 用于执行探活与建表的数据源 */
    private final ObjectProvider<DataSource> dataSourceProvider;

    /** 组件名称（日志与报错） */
    private final String component;

    /** 建表脚本文件名（mysql / pgsql 两个方言目录下同名） */
    private final String sqlFile;

    /** 待校验的表名列表 */
    private final List<String> tables;

    public TableInitializer(ObjectProvider<DataSource> dataSourceProvider, String component,
                            String sqlFile, List<String> tables) {
        this.dataSourceProvider = dataSourceProvider;
        this.component = component;
        this.sqlFile = sqlFile;
        this.tables = List.copyOf(tables);
    }

    /** 单例就绪后逐表探活，缺表则按方言自动建表，仍缺则阻止启动 */
    @Override
    public void afterSingletonsInstantiated() {
        DataSource dataSource = resolveDataSource();
        if (dataSource == null) {
            log.warn("[{}] 未解析到唯一 DataSource，跳过启动期建表检查；表缺失将在运行期报错", component);
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            Probe probe = probe(connection, tables);
            if (probe.missing().isEmpty()) {
                log.info("[{}] 启动期建表检查通过: {}", component, String.join(", ", tables));
                return;
            }
            initialize(connection, probe);
        } catch (SQLException e) {
            throw new IllegalStateException("[" + component + "] 启动期建表检查失败：无法获取数据库连接", e);
        }
    }

    /** 缺表时按当前数据库方言执行建表脚本，并复核结果 */
    private void initialize(Connection connection, Probe probe) throws SQLException {
        List<String> missing = probe.missing();
        Dialect dialect = Dialect.of(connection.getMetaData().getDatabaseProductName());
        if (dialect == null) {
            throw error(missing, "，且当前数据库不支持自动建表（仅内置 MySQL / PostgreSQL 脚本）"
                    + "，请手工执行组件 jar 内 sql/mysql/" + sqlFile + " 或 sql/pgsql/" + sqlFile + " 后重启应用",
                    probe.firstError());
        }

        String script = SQL_DIR + dialect.dir() + "/" + sqlFile;
        ClassPathResource resource = new ClassPathResource(script);
        if (!resource.exists()) {
            throw error(missing, "，且 jar 内未找到当前数据库（" + dialect.display() + "）对应的建表脚本 " + script,
                    probe.firstError());
        }

        log.info("[{}] 缺表 {}，按当前数据库（{}）执行建表脚本 {} 自动建表",
                component, missing, dialect.display(), script);
        try {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(resource, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw error(missing, "，自动建表脚本 " + script + " 执行失败: " + e.getMessage(), e);
        }

        Probe again = probe(connection, missing);
        if (!again.missing().isEmpty()) {
            throw error(again.missing(), "，已执行建表脚本 " + script + " 仍缺表"
                    + (again.firstError() == null ? "" : "（首个错误: " + again.firstError().getMessage() + "）"),
                    again.firstError());
        }
        log.info("[{}] 缺表已按建表脚本 {} 自动创建: {}", component, script, String.join(", ", missing));
    }

    /** 逐表探活（{@code SELECT 1 FROM t WHERE 1 = 0}），返回缺失表与首个探活错误 */
    private Probe probe(Connection connection, List<String> candidates) throws SQLException {
        List<String> missing = new ArrayList<>();
        SQLException firstError = null;
        for (String table : candidates) {
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0");
            } catch (SQLException e) {
                missing.add(table);
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        return new Probe(missing, firstError);
    }

    /** 缺表报错：统一前缀 + 缺失表清单 + 场景说明 */
    private IllegalStateException error(List<String> missing, String detail, Throwable cause) {
        String message = "[" + component + "] 启动期建表检查失败，缺少表: " + String.join(", ", missing) + detail;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    /** 解析用于探活与建表的数据源 */
    private DataSource resolveDataSource() {
        DataSource dataSource = dataSourceProvider.getIfUnique();
        if (dataSource != null) {
            return dataSource;
        }
        // 多个候选且无 @Primary 时，优先取 dynamic-datasource 的路由数据源（主库）
        return dataSourceProvider.stream()
                .filter(DynamicRoutingDataSource.class::isInstance)
                .findFirst()
                .orElse(null);
    }

    /** 探活结果：缺失表清单 + 首个错误（用于报错留痕） */
    private record Probe(List<String> missing, SQLException firstError) {
    }

    /** 自动建表支持的方言：jar 内脚本目录名 + 报错显示名 */
    private record Dialect(String dir, String display) {

        /** 按 JDBC 数据库产品名识别方言；不支持自动建表返回 {@code null} */
        static Dialect of(String productName) {
            String name = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (name.contains("mysql")) {
                return new Dialect("mysql", "MySQL");
            }
            if (name.contains("postgresql")) {
                return new Dialect("pgsql", "PostgreSQL");
            }
            return null;
        }
    }

}
