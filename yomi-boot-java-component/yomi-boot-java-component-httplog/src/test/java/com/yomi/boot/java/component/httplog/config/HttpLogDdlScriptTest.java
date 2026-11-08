package com.yomi.boot.java.component.httplog.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两套方言 DDL 校验：脚本在仓库根 sql/{mysql,pgsql}/（普通目录，不进 classpath/jar，测试直接读文件），
 * 两个方言都声明组件全部表，pgsql 脚本不含 MySQL 专属写法。
 */
class HttpLogDdlScriptTest {

    /** 组件表清单（脚本是唯一 DDL 来源，需与实体/仓储一致） */
    private static final List<String> TABLES = List.of("t_sys_http_log");

    /** MySQL 专属写法：pgsql 脚本里出现即执行失败（注释行已剔除） */
    private static final List<String> MYSQL_ONLY = List.of("`", "mediumtext", "datetime(", "tinyint",
            "engine=innodb", "on update current_timestamp");

    @Test
    void bothDialectsDeclareAllTables() throws IOException {
        String mysql = body("sql/mysql/t_sys_http_log.sql");
        String pgsql = body("sql/pgsql/t_sys_http_log.sql");

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

    /** 读仓库根 sql/ 脚本并剔除 -- 行注释、转小写（只保留真正的 SQL 语句） */
    private static String body(String path) throws IOException {
        Path file = repositoryRoot().resolve(path);
        assertTrue(Files.isRegularFile(file), "仓库根目录必须包含 " + path + "（实际路径 " + file + "）");
        return Files.readString(file, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);
    }

    /** 仓库根目录：从工作目录向上找到同时含 sql/mysql 与 sql/pgsql 的那层（sql 是普通目录，不进 classpath） */
    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("sql/mysql")) && Files.isDirectory(dir.resolve("sql/pgsql"))) {
                return dir;
            }
        }
        throw new IllegalStateException("未找到仓库根目录（需含 sql/mysql 与 sql/pgsql）: " + Path.of("").toAbsolutePath());
    }

}
