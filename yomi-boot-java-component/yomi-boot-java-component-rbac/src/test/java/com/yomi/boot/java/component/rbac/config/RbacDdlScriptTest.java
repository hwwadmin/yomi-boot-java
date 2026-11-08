package com.yomi.boot.java.component.rbac.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两套方言 DDL 的守卫：脚本在仓库根 sql/{mysql,pgsql}/（普通目录，不进 classpath/jar，测试直接读文件），
 * 两个方言都必须声明组件全部表（含内置超管种子），pgsql 脚本不含 MySQL 专属写法。
 */
class RbacDdlScriptTest {

    /** 组件表清单（脚本是唯一 DDL 来源，需与实体/仓储一致） */
    private static final List<String> TABLES = List.of("t_sys_rbac_perm", "t_sys_rbac_perm_group", "t_sys_rbac_role",
            "t_sys_rbac_user_role_rel", "t_sys_rbac_role_group_rel", "t_sys_rbac_group_perm_rel");

    /** MySQL 专属写法：pgsql 脚本里出现即执行失败（注释行已剔除） */
    private static final List<String> MYSQL_ONLY = List.of("`", "mediumtext", "datetime(", "tinyint",
            "engine=innodb", "on update current_timestamp");

    @Test
    void bothDialectsDeclareAllTablesAndSeed() throws IOException {
        String mysql = body("sql/mysql/t_sys_rbac.sql");
        String pgsql = body("sql/pgsql/t_sys_rbac.sql");

        for (String table : TABLES) {
            assertTrue(mysql.contains("create table if not exists `" + table + "`"),
                    "mysql 脚本必须声明 " + table);
            assertTrue(pgsql.contains("create table if not exists " + table),
                    "pgsql 脚本必须声明 " + table);
        }
        for (String token : MYSQL_ONLY) {
            assertFalse(pgsql.contains(token), "pgsql 脚本不得残留 MySQL 写法: " + token);
        }
        assertTrue(mysql.contains("insert into `t_sys_rbac_role`") && mysql.contains("'super_admin'"),
                "mysql 脚本必须含内置超管种子");
        assertTrue(pgsql.contains("insert into t_sys_rbac_role") && pgsql.contains("'super_admin'"),
                "pgsql 脚本必须含内置超管种子");
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
