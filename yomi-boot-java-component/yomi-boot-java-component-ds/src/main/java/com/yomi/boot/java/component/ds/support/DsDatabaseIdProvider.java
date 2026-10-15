package com.yomi.boot.java.component.ds.support;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 方言标识（MyBatis {@code databaseId}）解析：MySQL → {@code mysql}、PostgreSQL → {@code pgsql}，
 * 与组件 SQL 脚本目录 {@code sql/mysql|pgsql} 同名；mapper XML 用 {@code databaseId} 按方言各写一条语句，
 * 由 MyBatis 按当前数据源选择（避免为双库通用把 SQL 写成复杂/低效的一条）。
 * <p>
 * 两种失败分开处理：**库连不上**（宿主未配数据源，或库未就绪）返回 null、不阻断启动——组件的数据源
 * 预热与建表检查本就按"无数据源则跳过"处理，此时 DB 能力不启用；**库连上但方言不认识**则在启动期
 * 直接失败——只支持 MySQL / PostgreSQL，方言不明时继续跑会撞 {@code Invalid bound statement}。
 *
 * @author zwei
 */
@Slf4j
public class DsDatabaseIdProvider extends VendorDatabaseIdProvider {

    /** 产品名 → databaseId；父类按"产品名 contains 键"匹配（MySQL、PostgreSQL 的产品名即此写法） */
    public DsDatabaseIdProvider() {
        Properties mappings = new Properties();
        mappings.setProperty("MySQL", "mysql");
        mappings.setProperty("PostgreSQL", "pgsql");
        setProperties(mappings);
    }

    @Override
    public String getDatabaseId(DataSource dataSource) {
        String databaseId;
        try {
            // 父类对"取产品名失败"只包 SQLException，dynamic-datasource 无主数据源抛的是 RuntimeException，
            // 故这里按"任何取不到方言的情形"统一处理：不注册方言语句，也不阻断启动（无数据源宿主是受支持形态）
            databaseId = super.getDatabaseId(dataSource);
        } catch (Exception e) {
            log.debug("未取到数据库连接/方言标识，跳过方言解析（方言语句不注册）", e);
            return null;
        }
        if (databaseId == null) {
            throw new IllegalStateException(
                    "无法识别数据源方言（只支持 MySQL / PostgreSQL）: " + describe(dataSource));
        }
        return databaseId;
    }

    /** 失败路径专用：把产品名拼进报错（正常路径不再开连接） */
    private static String describe(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            return "取数据库连接失败: " + e.getMessage();
        }
    }

}
