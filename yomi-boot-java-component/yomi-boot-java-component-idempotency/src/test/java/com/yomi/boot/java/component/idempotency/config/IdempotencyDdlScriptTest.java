package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两套方言 DDL 的守卫：脚本在 classpath（随 jar 打包，见根 pom 的 sql resources），
 * 两个方言都要声明组件全部表，pgsql 脚本里不能有 MySQL 专属写法，列宽与 {@link IdempotencyKey} 的上限常量同源。
 */
class IdempotencyDdlScriptTest {

    /** 组件声明的表清单（与装配类里的 TableInitializer 一致） */
    private static final List<String> TABLES = List.of("sys_idempotency_record");

    /** MySQL 专属写法：pgsql 脚本里出现即执行失败（注释行已剔除） */
    private static final List<String> MYSQL_ONLY = List.of("`", "mediumtext", "datetime(", "tinyint",
            "engine=innodb", "on update current_timestamp");

    @Test
    void bothDialectsDeclareAllTables() throws IOException {
        String mysql = body("sql/mysql/sys_idempotency_record.sql");
        String pgsql = body("sql/pgsql/sys_idempotency_record.sql");

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
        String mysql = body("sql/mysql/sys_idempotency_record.sql");
        String pgsql = body("sql/pgsql/sys_idempotency_record.sql");

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
