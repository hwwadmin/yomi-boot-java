package com.yomi.boot.java.component.rbac.support;

import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RBAC 通用辅助
 *
 * @author zwei
 */
public final class RbacSupport {

    private RbacSupport() {
    }

    /** 权限/组/角色编码规范化（trim） */
    public static String normalizeCode(String code) {
        return StringUtils.trimToEmpty(code);
    }

    /** 状态码规范化，非法值回落为默认启用 */
    public static int statusOrDefault(Integer status) {
        return RbacStatus.of(status).getCode();
    }

    /** 是否为超管角色（按 code） */
    public static boolean isSuperRole(RbacRole role) {
        return role != null && RbacBuiltinRole.SUPER_ADMIN.equals(role.getCode());
    }

    /** 内置角色（含超管），不可删除、不可禁用 */
    public static boolean isBuiltinRole(RbacRole role) {
        if (role == null) {
            return false;
        }
        if (isSuperRole(role)) {
            return true;
        }
        return role.getBuiltin() != null && role.getBuiltin() == 1;
    }

    /** 去重、过滤非法 ID，保持输入顺序 */
    public static List<Long> uniqueIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                seen.add(id);
            }
        }
        return new ArrayList<>(seen);
    }

}
