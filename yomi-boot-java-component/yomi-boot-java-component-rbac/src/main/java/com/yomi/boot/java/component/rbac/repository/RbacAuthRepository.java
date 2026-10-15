package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacPermType;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import com.yomi.boot.java.component.rbac.eval.RbacAuthEvaluator;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacAuthMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 鉴权 SQL 访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class RbacAuthRepository implements IRbacAuthRepository {

    /** RBAC 启用状态码 */
    private static final int ACTIVE = RbacStatus.ACTIVE.getCode();

    /** 授权相关 MyBatis Mapper */
    private final RbacAuthMapper authMapper;

    /** 用户是否绑定启用中的超管角色 */
    public boolean userHasEnabledSuperAdmin(long userId) {
        return authMapper.countUserHasEnabledSuperAdmin(userId, RbacBuiltinRole.SUPER_ADMIN, ACTIVE) > 0;
    }

    /** SQL 路径：是否存在匹配的 API 权限（精确相等或 Ant 通配，与投影模式同语义） */
    public boolean existsHttpAccess(long userId, String method, String path) {
        List<String> paths = authMapper.listHttpAccessPaths(userId, method, RbacPermType.API.getCode(), ACTIVE);
        for (String candidate : paths) {
            if (RbacAuthEvaluator.matchesApiPath(candidate, path)) {
                return true;
            }
        }
        return false;
    }

    /** 用户可见的启用目录权限 */
    public List<RbacPerm> listMenuByUserId(long userId) {
        return authMapper.listMenuByUserId(userId, RbacPermType.MENU.getCode(), ACTIVE);
    }

    /** 全部启用的目录权限（超管用） */
    public List<RbacPerm> listEnabledMenus() {
        return authMapper.listEnabledMenus(RbacPermType.MENU.getCode(), ACTIVE);
    }

    /** 当前持有启用超管角色的用户 ID 列表 */
    public List<Long> listUserIdsWithEnabledSuperAdmin() {
        return authMapper.listUserIdsWithEnabledSuperAdmin(RbacBuiltinRole.SUPER_ADMIN, ACTIVE);
    }

}
