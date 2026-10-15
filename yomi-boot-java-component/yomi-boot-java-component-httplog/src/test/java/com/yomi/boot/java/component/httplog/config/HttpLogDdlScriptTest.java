package com.yomi.boot.java.component.httplog.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两套方言 DDL 的守卫：脚本在 classpath（随 jar 打包，见根 pom 的 sql resources），
 * 两个方言都必须声明组件全部表，且 pgsql 脚本不得残留 MySQL 专属写法。
 */
class HttpLogDdlScriptTest {

    /** 组件声明的表清单（与装配类里的 TableInitializer 一致） */
    private static final List<String> TABLES = List.of("sys_http_log");

    /** MySQL 专属写法：pgsql 脚本里出现即执行失败（注释行已剔除） */
    private static final List<String> MYSQL_ONLY = List.of("`", "mediumtext", "datetime(", "tinyint",
            "engine=innodb", "on update current_timestamp");

    @Test
    void bothDialectsDeclareAllTables() throws IOException {
        String mysql = body("sql/mysql/sys_http_log.sql");
        String pgsql = body("sql/pgsql/sys_http_log.sql");

        for (String table : TABLES) {
            assertTrue(mysql.contains("create table if not exists `" + table + "`"),
                    "mysql 脚本必须声明 " + table);
            assertTrue(pgsql.contains("create table if not exists " + table),
                    "pgsql 脚本必须声明 " + table);
        }
        for (String token : MYSQL_ONLY) {
            assertFalse(pgsql.contains(token), "pgsql 脚本不得残留 MySQL 写法: " + token);
        }
    }

    /** 读 classpath 脚本并剔除 -- 行注释、转小写（守卫只看真正的 SQL 语句） */
    private static String body(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertTrue(resource.exists(), "classpath 必须包含 " + path + "（jar 打包配置见根 pom resources）");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce("", (left, right) -> left + "\n" + right)
                    .toLowerCase(Locale.ROOT);
        }
    }

}
