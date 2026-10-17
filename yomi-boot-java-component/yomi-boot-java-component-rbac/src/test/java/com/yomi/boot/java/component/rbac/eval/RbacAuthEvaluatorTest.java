package com.yomi.boot.java.component.rbac.eval;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacPermType;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RbacAuthEvaluatorTest {

    @Test
    void superAdminBypassesHttp() {
        RbacSnapshot snap = baseSnapshot();
        RbacRole superRole = role(1L, RbacBuiltinRole.SUPER_ADMIN, RbacStatus.ACTIVE.getCode());
        snap.setRoles(List.of(superRole));
        snap.setUserRoles(Map.of(100L, List.of(1L)));
        assertTrue(RbacAuthEvaluator.canAccessHttp(snap, 100L, "POST", "/any"));
    }

    @Test
    void apiExactMatch() {
        RbacSnapshot snap = baseSnapshot();
        RbacRole role = role(2L, "ops", RbacStatus.ACTIVE.getCode());
        RbacPermGroup group = group(10L, RbacStatus.ACTIVE.getCode());
        RbacPerm api = apiPerm(20L, "GET", "/api/demo");
        snap.setRoles(List.of(role));
        snap.setGroups(List.of(group));
        snap.setPerms(List.of(api));
        snap.setUserRoles(Map.of(1L, List.of(2L)));
        snap.setRoleGroups(Map.of(2L, List.of(10L)));
        snap.setGroupPerms(Map.of(10L, List.of(20L)));
        assertTrue(RbacAuthEvaluator.canAccessHttp(snap, 1L, "get", "/api/demo"));
        assertFalse(RbacAuthEvaluator.canAccessHttp(snap, 1L, "POST", "/api/demo"));
    }

    /** 构造 Evaluator 基础快照 */
    private static RbacSnapshot baseSnapshot() {
        return RbacSnapshot.empty();
    }

    // ==================== API 路径通配：精确优先，其次 Ant 匹配 ====================

    @Test
    void doubleStarMatchesAcrossSegments() {
        assertTrue(RbacAuthEvaluator.matchesApiPath("/api/user/**", "/api/user/42"));
        assertTrue(RbacAuthEvaluator.matchesApiPath("/api/user/**", "/api/user/42/roles"));
        assertFalse(RbacAuthEvaluator.matchesApiPath("/api/user/**", "/api/users/42"),
                "段名不同不应命中（* 不是通配全部）");
    }

    @Test
    void singleStarMatchesOneSegmentOnly() {
        assertTrue(RbacAuthEvaluator.matchesApiPath("/api/user/*", "/api/user/42"));
        assertFalse(RbacAuthEvaluator.matchesApiPath("/api/user/*", "/api/user/42/roles"));
        assertFalse(RbacAuthEvaluator.matchesApiPath("/api/user/*", "/api/user"));
    }

    @Test
    void pathVariableMatchesOneSegment() {
        assertTrue(RbacAuthEvaluator.matchesApiPath("/api/user/{id}", "/api/user/42"));
        assertFalse(RbacAuthEvaluator.matchesApiPath("/api/user/{id}", "/api/user/42/roles"));
    }

    @Test
    void exactPathStillWinsWithoutWildcard() {
        assertTrue(RbacAuthEvaluator.matchesApiPath("/api/demo", "/api/demo"));
        assertFalse(RbacAuthEvaluator.matchesApiPath("/api/demo", "/api/demo/extra"));
        assertFalse(RbacAuthEvaluator.matchesApiPath("/api/demo", "/api/demo2"));
    }

    /** 通配权限在完整求值链路上生效（角色 → 权限组 → 权限 → 路径匹配） */
    @Test
    void wildcardApiPermIsEffectiveInEvaluationChain() {
        RbacSnapshot snap = baseSnapshot();
        snap.setRoles(List.of(role(3L, "ops", RbacStatus.ACTIVE.getCode())));
        snap.setGroups(List.of(group(11L, RbacStatus.ACTIVE.getCode())));
        snap.setPerms(List.of(apiPerm(21L, "GET", "/api/user/**")));
        snap.setUserRoles(Map.of(2L, List.of(3L)));
        snap.setRoleGroups(Map.of(3L, List.of(11L)));
        snap.setGroupPerms(Map.of(11L, List.of(21L)));

        assertTrue(RbacAuthEvaluator.canAccessHttp(snap, 2L, "GET", "/api/user/42/detail"),
                "登记 /api/user/** 后，带路径参数的业务接口应放行");
        assertFalse(RbacAuthEvaluator.canAccessHttp(snap, 2L, "POST", "/api/user/42/detail"),
                "方法不同仍拒绝（默认拒绝方向不变）");
    }

    /** 构造测试角色实体 */
    private static RbacRole role(long id, String code, int status) {
        RbacRole r = new RbacRole();
        r.setId(id);
        r.setCode(code);
        r.setStatus(status);
        return r;
    }

    /** 构造测试权限组实体 */
    private static RbacPermGroup group(long id, int status) {
        RbacPermGroup g = new RbacPermGroup();
        g.setId(id);
        g.setStatus(status);
        return g;
    }

    /** 构造 API 类型权限 */
    private static RbacPerm apiPerm(long id, String method, String path) {
        RbacPerm p = new RbacPerm();
        p.setId(id);
        p.setType(RbacPermType.API.getCode());
        p.setStatus(RbacStatus.ACTIVE.getCode());
        p.setHttpMethod(method);
        p.setHttpPath(path);
        return p;
    }

}
