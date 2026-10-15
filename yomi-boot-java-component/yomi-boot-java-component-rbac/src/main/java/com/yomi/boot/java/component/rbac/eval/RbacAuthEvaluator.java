package com.yomi.boot.java.component.rbac.eval;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacPermType;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.AntPathMatcher;

import java.util.*;

/**
 * 授权快照求值（纯函数，单点求值）
 * <p>
 * API 权限路径匹配规则：精确相等优先；登记路径含 Ant 通配（{@code *} 单段、{@code **} 多段、
 * {@code {var}} 单段变量、{@code ?} 单字符）时按 pattern 匹配。默认拒绝方向不变。
 * </p>
 *
 * @author zwei
 */
public final class RbacAuthEvaluator {

    /** Ant 风格路径匹配器 */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private RbacAuthEvaluator() {
    }

    /**
     * 单条 API 权限路径是否匹配请求路径：精确相等优先，含通配符的登记路径走 Ant 匹配。
     * <p>公开供 SQL 鉴权路径（非投影模式）复用，保证两种模式匹配语义一致</p>
     */
    public static boolean matchesApiPath(String permPath, String requestPath) {
        if (StringUtils.equals(permPath, requestPath)) {
            return true;
        }
        return hasPathWildcard(permPath) && PATH_MATCHER.match(permPath, requestPath);
    }

    /** 判断 API 路径是否含通配符 */
    private static boolean hasPathWildcard(String path) {
        return path != null && (path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('{') >= 0);
    }

    /** 快照上判断 HTTP 是否允许；method 大写，path 不含 query（内部自建索引；热点路径请用带索引的重载） */
    public static boolean canAccessHttp(RbacSnapshot snap, long userId, String method, String path) {
        return canAccessHttp(snap, buildIndex(snap), userId, method, path);
    }

    /** 快照上判断 HTTP 是否允许（复用调用方缓存的索引，避免每请求重建） */
    public static boolean canAccessHttp(RbacSnapshot snap, EvalIndex idx, long userId, String method, String path) {
        if (snap == null || userId <= 0) {
            return false;
        }
        if (userHasEnabledSuperAdmin(idx, snap, userId)) {
            return true;
        }
        method = StringUtils.upperCase(StringUtils.trimToEmpty(method));
        path = StringUtils.trimToEmpty(path);
        if (method.isEmpty() || path.isEmpty()) {
            return false;
        }
        return existsHttpAccess(idx, snap, userId, method, path);
    }

    /** 快照上列出用户可见目录；超管为全部启用 menu（内部自建索引） */
    public static List<RbacPerm> listUiMenus(RbacSnapshot snap, long userId) {
        return listUiMenus(snap, buildIndex(snap), userId);
    }

    /** 快照上列出用户可见目录（复用调用方缓存的索引） */
    public static List<RbacPerm> listUiMenus(RbacSnapshot snap, EvalIndex idx, long userId) {
        if (snap == null || userId <= 0) {
            return List.of();
        }
        if (userHasEnabledSuperAdmin(idx, snap, userId)) {
            return listEnabledMenus(snap);
        }
        return listMenuByUserId(idx, snap, userId);
    }

    /** 判断用户是否拥有启用的超管角色 */
    private static boolean userHasEnabledSuperAdmin(EvalIndex idx, RbacSnapshot snap, long userId) {
        for (Long roleId : snap.getUserRoles().getOrDefault(userId, List.of())) {
            RbacRole role = idx.roles.get(roleId);
            if (role == null) {
                continue;
            }
            if (RbacBuiltinRole.SUPER_ADMIN.equals(role.getCode())
                    && RbacStatus.ACTIVE.getCode() == RbacStatus.orDefault(role.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /** 按角色-组-权限链判断 HTTP 是否放行 */
    private static boolean existsHttpAccess(EvalIndex idx, RbacSnapshot snap, long userId, String method, String path) {
        for (Long roleId : snap.getUserRoles().getOrDefault(userId, List.of())) {
            RbacRole role = idx.roles.get(roleId);
            if (role == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(role.getStatus())) {
                continue;
            }
            for (Long groupId : snap.getRoleGroups().getOrDefault(roleId, List.of())) {
                RbacPermGroup group = idx.groups.get(groupId);
                if (group == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(group.getStatus())) {
                    continue;
                }
                for (Long permId : snap.getGroupPerms().getOrDefault(groupId, List.of())) {
                    RbacPerm perm = idx.perms.get(permId);
                    if (perm == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(perm.getStatus())) {
                        continue;
                    }
                    if (!RbacPermType.API.getCode().equalsIgnoreCase(StringUtils.trimToEmpty(perm.getType()))) {
                        continue;
                    }
                    if (StringUtils.equalsIgnoreCase(perm.getHttpMethod(), method)
                            && matchesApiPath(perm.getHttpPath(), path)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 汇总用户角色下可见的菜单权限 */
    private static List<RbacPerm> listMenuByUserId(EvalIndex idx, RbacSnapshot snap, long userId) {
        Set<Long> seen = new HashSet<>();
        List<RbacPerm> out = new ArrayList<>();
        for (Long roleId : snap.getUserRoles().getOrDefault(userId, List.of())) {
            RbacRole role = idx.roles.get(roleId);
            if (role == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(role.getStatus())) {
                continue;
            }
            for (Long groupId : snap.getRoleGroups().getOrDefault(roleId, List.of())) {
                RbacPermGroup group = idx.groups.get(groupId);
                if (group == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(group.getStatus())) {
                    continue;
                }
                for (Long permId : snap.getGroupPerms().getOrDefault(groupId, List.of())) {
                    if (!seen.add(permId)) {
                        continue;
                    }
                    RbacPerm perm = idx.perms.get(permId);
                    if (perm == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(perm.getStatus())) {
                        continue;
                    }
                    if (!RbacPermType.MENU.getCode().equalsIgnoreCase(StringUtils.trimToEmpty(perm.getType()))) {
                        continue;
                    }
                    out.add(perm);
                }
            }
        }
        return out;
    }

    /** 列出快照中全部启用的菜单权限 */
    private static List<RbacPerm> listEnabledMenus(RbacSnapshot snap) {
        List<RbacPerm> out = new ArrayList<>();
        for (RbacPerm perm : snap.getPerms()) {
            if (perm == null || RbacStatus.ACTIVE.getCode() != RbacStatus.orDefault(perm.getStatus())) {
                continue;
            }
            if (!RbacPermType.MENU.getCode().equalsIgnoreCase(StringUtils.trimToEmpty(perm.getType()))) {
                continue;
            }
            out.add(perm);
        }
        return out;
    }

    /** 构建三类 id 索引（Hot path 应缓存复用：见 RbacAuthIndexCache） */
    public static EvalIndex buildIndex(RbacSnapshot snap) {
        Map<Long, RbacRole> roles = new HashMap<>();
        for (RbacRole role : snap.getRoles()) {
            if (role != null && role.getId() != null) {
                roles.put(role.getId(), role);
            }
        }
        Map<Long, RbacPermGroup> groups = new HashMap<>();
        for (RbacPermGroup group : snap.getGroups()) {
            if (group != null && group.getId() != null) {
                groups.put(group.getId(), group);
            }
        }
        Map<Long, RbacPerm> perms = new HashMap<>();
        for (RbacPerm perm : snap.getPerms()) {
            if (perm != null && perm.getId() != null) {
                perms.put(perm.getId(), perm);
            }
        }
        return new EvalIndex(roles, groups, perms);
    }

    /** id → 实体索引（由 RbacSnapshot 构建，快照不变则索引可复用） */
    public record EvalIndex(Map<Long, RbacRole> roles, Map<Long, RbacPermGroup> groups, Map<Long, RbacPerm> perms) {
    }

}
