package com.yomi.boot.java.component.rbac.enums;

/**
 * 内置角色编码
 *
 * @author zwei
 */
public final class RbacBuiltinRole {

    /** 超管角色编码（全量放行 HTTP / 菜单） */
    public static final String SUPER_ADMIN = "super_admin";

    /** 与 sql/sys_rbac.sql 种子数据主键一致 */
    public static final long SUPER_ADMIN_ROLE_ID = 900_000_000_000_000_001L;

    private RbacBuiltinRole() {
    }

}
