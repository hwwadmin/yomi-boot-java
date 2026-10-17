package com.yomi.boot.java.component.rbac.config;

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
 * 两个方言都必须声明组件全部表（含内置超管种子），pgsql 脚本不含 MySQL 专属写法。
 */
class RbacDdlScriptTest {

    /** 组件声明的表清单（与装配类里的 TableInitializer 一致） */
    private static final List<String> TABLES = List.of("sys_rbac_perm", "sys_rbac_perm_group", "sys_rbac_role",
            "sys_rbac_user_role_rel", "sys_rbac_role_group_rel", "sys_rbac_group_perm_rel");

    /** MySQL 专属写法：pgsql 脚本里出现即执行失败（注释行已剔除） */
    private static final List<String> MYSQL_ONLY = List.of("`", "mediumtext", "datetime(", "tinyint",
            "engine=innodb", "on update current_timestamp");

    @Test
    void bothDialectsDeclareAllTablesAndSeed() throws IOException {
        String mysql = body("sql/mysql/sys_rbac.sql");
        String pgsql = body("sql/pgsql/sys_rbac.sql");

        for (String table : TABLES) {
            assertTrue(mysql.contains("create table if not exists `" + table + "`"),
                    "mysql 脚本必须声明 " + table);
            assertTrue(pgsql.contains("create table if not exists " + table),
                    "pgsql 脚本必须声明 " + table);
        }
        for (String token : MYSQL_ONLY) {
            assertFalse(pgsql.contains(token), "pgsql 脚本不得残留 MySQL 写法: " + token);
        }
        assertTrue(mysql.contains("insert into `sys_rbac_role`") && mysql.contains("'super_admin'"),
                "mysql 脚本必须含内置超管种子");
        assertTrue(pgsql.contains("insert into sys_rbac_role") && pgsql.contains("'super_admin'"),
                "pgsql 脚本必须含内置超管种子");
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
