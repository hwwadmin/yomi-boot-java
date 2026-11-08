package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两套方言 DDL 的守卫：脚本在仓库根 sql/{mysql,pgsql}/（普通目录，不进 classpath/jar，测试直接读文件），
 * 两个方言都要声明组件全部表，pgsql 脚本里不能有 MySQL 专属写法，列宽与 {@link IdempotencyKey} 的上限常量同源。
 */
class IdempotencyDdlScriptTest {

    /** 组件表清单（脚本是唯一 DDL 来源，需与实体/仓储一致） */
    private static final List<String> TABLES = List.of("t_sys_idempotency_record");

    /** MySQL 专属写法：pgsql 脚本里出现即执行失败（注释行已剔除） */
    private static final List<String> MYSQL_ONLY = List.of("`", "mediumtext", "datetime(", "tinyint",
            "engine=innodb", "on update current_timestamp");

    @Test
    void bothDialectsDeclareAllTables() throws IOException {
        String mysql = body("sql/mysql/t_sys_idempotency_record.sql");
        String pgsql = body("sql/pgsql/t_sys_idempotency_record.sql");

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

    /** 列宽与代码里的上限常量同源：DDL 改了宽度而常量没跟上（或反之）时这里先红 */
    @Test
    void columnWidthsMatchKeyLimits() throws IOException {
        String mysql = body("sql/mysql/t_sys_idempotency_record.sql");
        String pgsql = body("sql/pgsql/t_sys_idempotency_record.sql");

        assertEquals(IdempotencyKey.MAX_NAMESPACE_LENGTH, width(mysql, "`namespace`"), "mysql namespace 列宽");
        assertEquals(IdempotencyKey.MAX_KEY_LENGTH, width(mysql, "`idempotency_key`"), "mysql idempotency_key 列宽");
        assertEquals(IdempotencyKey.REQUEST_HASH_LENGTH, width(mysql, "`request_hash`"), "mysql request_hash 列宽");
        assertEquals(IdempotencyKey.MAX_NAMESPACE_LENGTH, width(pgsql, "namespace"), "pgsql namespace 列宽");
        assertEquals(IdempotencyKey.MAX_KEY_LENGTH, width(pgsql, "idempotency_key"), "pgsql idempotency_key 列宽");
        assertEquals(IdempotencyKey.REQUEST_HASH_LENGTH, width(pgsql, "request_hash"), "pgsql request_hash 列宽");
    }

    /** 从脚本里取列宽：列名后紧跟的 varchar(n) */
    private static int width(String script, String column) {
        Matcher matcher = Pattern.compile(Pattern.quote(column) + "\\s+varchar\\((\\d+)\\)").matcher(script);
        assertTrue(matcher.find(), "脚本里找不到 " + column + " 的 varchar 定义");
        return Integer.parseInt(matcher.group(1));
    }

    /** 读仓库根 sql/ 脚本并剔除 -- 行注释、转小写（守卫只看真正的 SQL 语句） */
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
