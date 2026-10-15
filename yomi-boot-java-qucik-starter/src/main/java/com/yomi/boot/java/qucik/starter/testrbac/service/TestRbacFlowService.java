package com.yomi.boot.java.qucik.starter.testrbac.service;

import com.yomi.boot.java.component.rbac.IRbacAdminService;
import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacPermType;
import com.yomi.boot.java.component.rbac.input.BindUserRolesInput;
import com.yomi.boot.java.component.rbac.input.CreatePermGroupInput;
import com.yomi.boot.java.component.rbac.input.CreatePermInput;
import com.yomi.boot.java.component.rbac.input.CreateRoleInput;
import com.yomi.boot.java.component.rbac.model.RbacPermModel;
import com.yomi.boot.java.component.rbac.model.RbacPermGroupModel;
import com.yomi.boot.java.component.rbac.model.RbacRoleModel;
import com.yomi.boot.java.component.rbac.repository.IRbacAuthRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacPermGroupRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacPermRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacRoleRepository;
import com.yomi.boot.java.qucik.starter.testrbac.TestRbacDemoConstants;
import com.yomi.boot.java.qucik.starter.testrbac.dto.TestRbacFlowResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 准备演示权限数据并校验 {@link IRbacAuthService} 行为
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.rbac.enabled", havingValue = "true")
public class TestRbacFlowService {

    /** 创建演示权限数据 */
    private final IRbacAdminService rbacAdminService;

    /** 校验 canAccess */
    private final IRbacAuthService rbacAuthService;
    /** 演示数据查询 */
    /** 查询演示权限 */
    private final IRbacPermRepository permRepository;

    /** 查询演示权限组 */
    private final IRbacPermGroupRepository permGroupRepository;

    /** 查询演示角色 */
    private final IRbacRoleRepository roleRepository;

    /** 超管判定等 */
    private final IRbacAuthRepository authRepository;

    /** 执行 RBAC 鉴权演示步骤 */
    public TestRbacFlowResult runFlow() {
        TestRbacFlowResult result = new TestRbacFlowResult();
        result.setProtectedPath(TestRbacDemoConstants.PROTECTED_PATH);
        result.setAllowedUserId(TestRbacDemoConstants.USER_WITH_PERM);
        result.setDeniedUserId(TestRbacDemoConstants.USER_WITHOUT_PERM);

        clearUserRoles(TestRbacDemoConstants.USER_WITHOUT_PERM, result.getSteps());

        Long permId = ensureApiPerm(result.getSteps());
        Long groupId = ensureGroup(result.getSteps());
        rbacAdminService.bindGroupPerms(groupId, List.of(permId), TestRbacDemoConstants.USER_WITH_PERM);
        result.getSteps().add("3.绑定: 权限组已关联 API 权限");

        Long roleId = ensureRole(result.getSteps());
        rbacAdminService.bindRoleGroups(roleId, List.of(groupId), TestRbacDemoConstants.USER_WITH_PERM);
        result.getSteps().add("4.绑定: 角色已关联权限组");

        BindUserRolesInput bind = new BindUserRolesInput();
        bind.setUserId(TestRbacDemoConstants.USER_WITH_PERM);
        bind.setRoleIds(List.of(roleId));
        bind.setActorId(TestRbacDemoConstants.USER_WITH_PERM);
        rbacAdminService.bindUserRoles(bind);
        result.getSteps().add("5.绑定: 用户 " + TestRbacDemoConstants.USER_WITH_PERM + " 已授予演示角色");

        clearUserRoles(TestRbacDemoConstants.USER_WITHOUT_PERM, result.getSteps());

        rbacAuthService.refreshProjection();
        result.getSteps().add("6.刷新: 鉴权投影已 refresh");

        boolean allowed = rbacAuthService.canAccessHttp(
                TestRbacDemoConstants.USER_WITH_PERM,
                TestRbacDemoConstants.HTTP_METHOD,
                TestRbacDemoConstants.PROTECTED_PATH);
        if (!allowed) {
            throw new IllegalStateException("有权限用户应允许访问 " + TestRbacDemoConstants.PROTECTED_PATH);
        }
        result.getSteps().add("7.校验: 有权限用户 canAccessHttp=true");

        boolean denied = rbacAuthService.canAccessHttp(
                TestRbacDemoConstants.USER_WITHOUT_PERM,
                TestRbacDemoConstants.HTTP_METHOD,
                TestRbacDemoConstants.PROTECTED_PATH);
        if (denied) {
            throw new IllegalStateException("无权限用户应拒绝访问 " + TestRbacDemoConstants.PROTECTED_PATH
                    + "（请检查用户 " + TestRbacDemoConstants.USER_WITHOUT_PERM + " 是否仍绑定超管等角色）");
        }
        result.getSteps().add("8.校验: 无权限用户 canAccessHttp=false");
        result.getSteps().add("9.提示: HTTP 探测请调用 GET /test/ds/test-rbac/issue-token?userId=...");
        result.getSteps().add("流程通过");
        return result;
    }

    /** 清空演示用户的角色绑定 */
    private void clearUserRoles(long userId, List<String> steps) {
        BindUserRolesInput clear = new BindUserRolesInput();
        clear.setUserId(userId);
        clear.setRoleIds(List.of());
        clear.setActorId(TestRbacDemoConstants.USER_WITH_PERM);
        rbacAdminService.bindUserRoles(clear);
        if (authRepository.userHasEnabledSuperAdmin(userId)) {
            throw new IllegalStateException("用户 " + userId + " 仍为启用超管，无法作为无权限探测账号");
        }
        steps.add("绑定: 用户 " + userId + " 角色已清空");
    }

    /** 确保演示数据：ApiPerm */
    private Long ensureApiPerm(List<String> steps) {
        RbacPerm existing = permRepository.findByCode(TestRbacDemoConstants.PERM_CODE);
        if (existing != null && existing.exist()) {
            steps.add("1.权限: 复用已有 " + TestRbacDemoConstants.PERM_CODE);
            return existing.getId();
        }
        CreatePermInput input = new CreatePermInput();
        input.setCode(TestRbacDemoConstants.PERM_CODE);
        input.setName("RBAC 演示 API");
        input.setType(RbacPermType.API.getCode());
        input.setHttpMethod(TestRbacDemoConstants.HTTP_METHOD);
        input.setHttpPath(TestRbacDemoConstants.PROTECTED_PATH);
        input.setRemark("quick-starter testrbac");
        input.setActorId(TestRbacDemoConstants.USER_WITH_PERM);
        RbacPermModel created = rbacAdminService.createPerm(input);
        steps.add("1.权限: 新建 API " + TestRbacDemoConstants.PROTECTED_PATH);
        return created.getId();
    }

    /** 确保演示数据：Group */
    private Long ensureGroup(List<String> steps) {
        RbacPermGroup existing = permGroupRepository.findByCode(TestRbacDemoConstants.GROUP_CODE);
        if (existing != null && existing.exist()) {
            steps.add("2.权限组: 复用已有 " + TestRbacDemoConstants.GROUP_CODE);
            return existing.getId();
        }
        CreatePermGroupInput input = new CreatePermGroupInput();
        input.setCode(TestRbacDemoConstants.GROUP_CODE);
        input.setName("RBAC 演示组");
        input.setRemark("quick-starter testrbac");
        input.setActorId(TestRbacDemoConstants.USER_WITH_PERM);
        RbacPermGroupModel created = rbacAdminService.createPermGroup(input);
        steps.add("2.权限组: 新建 " + TestRbacDemoConstants.GROUP_CODE);
        return created.getId();
    }

    /** 确保演示数据：Role */
    private Long ensureRole(List<String> steps) {
        RbacRole existing = roleRepository.findByCode(TestRbacDemoConstants.ROLE_CODE);
        if (existing != null && existing.exist()) {
            steps.add("角色: 复用已有 " + TestRbacDemoConstants.ROLE_CODE);
            return existing.getId();
        }
        CreateRoleInput input = new CreateRoleInput();
        input.setCode(TestRbacDemoConstants.ROLE_CODE);
        input.setName("RBAC 演示角色");
        input.setRemark("quick-starter testrbac");
        input.setActorId(TestRbacDemoConstants.USER_WITH_PERM);
        RbacRoleModel created = rbacAdminService.createRole(input);
        steps.add("角色: 新建 " + TestRbacDemoConstants.ROLE_CODE);
        return created.getId();
    }

}
