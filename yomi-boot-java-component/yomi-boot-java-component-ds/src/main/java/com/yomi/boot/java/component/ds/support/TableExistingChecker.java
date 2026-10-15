package com.yomi.boot.java.component.ds.support;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动期校验组件依赖的数据表是否存在；缺表时阻止应用启动。
 * <p>
 * 在所有单例 Bean 初始化完成后执行一次（早于 Web 对外服务）。
 * 使用 {@code SELECT 1 FROM t WHERE 1 = 0} 仅验证表可访问、不读取数据行。
 * </p>
 *
 * @author zwei
 */
@Slf4j
public class TableExistingChecker implements SmartInitializingSingleton {

    /** 用于执行探活 SQL */
    private final ObjectProvider<DataSource> dataSourceProvider;

    /** 组件名称（日志与报错） */
    private final String component;

    /** 建表 SQL 在组件 jar 内的路径提示 */
    private final String sqlHint;

    /** 待校验的表名列表 */
    private final List<String> tables;

    public TableExistingChecker(ObjectProvider<DataSource> dataSourceProvider, String component,
                                String sqlHint, List<String> tables) {
        this.dataSourceProvider = dataSourceProvider;
        this.component = component;
        this.sqlHint = sqlHint;
        this.tables = List.copyOf(tables);
    }

    /** 单例就绪后逐表探活，缺表则阻止启动 */
    @Override
    public void afterSingletonsInstantiated() {
        DataSource dataSource = resolveDataSource();
        if (dataSource == null) {
            log.warn("[{}] 未解析到唯一 DataSource，跳过启动期建表检查；表缺失将在运行期报错", component);
            return;
        }

        List<String> missing = new ArrayList<>();
        SQLException firstError = null;
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0");
                } catch (SQLException e) {
                    missing.add(table);
                    if (firstError == null) {
                        firstError = e;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("[" + component + "] 启动期建表检查失败：无法获取数据库连接", e);
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("[" + component + "] 启动期建表检查失败，缺少表: " + String.join(", ", missing)
                    + "（首个错误: " + firstError.getMessage() + "），请先执行组件 jar 内 " + sqlHint + " 后重启应用",
                    firstError);
        }
        log.info("[{}] 启动期建表检查通过: {}", component, String.join(", ", tables));
    }

    /** 解析用于探活的数据源 */
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

}
