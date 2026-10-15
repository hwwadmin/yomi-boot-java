package com.yomi.boot.java.qucik.starter.testrbac;

/**
 * RBAC 流程测试固定标识（可重复执行 flow，按 code 幂等复用数据）
 */
public final class TestRbacDemoConstants {

    /** 演示用权限码 */
    public static final String PERM_CODE = "test_rbac_demo_api";
    /** 演示用权限组码 */
    public static final String GROUP_CODE = "test_rbac_demo_group";
    /** 演示用角色码 */
    public static final String ROLE_CODE = "test_rbac_demo_role";

    /** 演示：有权限的用户 ID */
    public static final long USER_WITH_PERM = 910_001L;
    /** 演示：无权限的用户 ID */
    public static final long USER_WITHOUT_PERM = 910_002L;

    /** 演示：受保护接口 HTTP 方法 */
    public static final String HTTP_METHOD = "GET";
    /** 演示：受保护接口路径 */
    public static final String PROTECTED_PATH = "/test/rbac/protected";

    private TestRbacDemoConstants() {
    }

}
