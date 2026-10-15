package com.yomi.boot.java.component.rbac;

import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.rbac.input.*;
import com.yomi.boot.java.component.rbac.model.*;

import java.util.List;
import java.util.Map;

/**
 * RBAC 管理面：权限、权限组、角色及绑定关系的 CRUD 与状态维护。
 * <p>写操作成功后由实现类触发鉴权投影刷新（若已启用）</p>
 *
 * @author zwei
 */
public interface IRbacAdminService {

    // —— 权限 ——

    /** 新建权限（menu 或 api） */
    RbacPermModel createPerm(CreatePermInput input);

    /** 更新权限；不可改 code、type */
    RbacPermModel updatePerm(UpdatePermInput input);

    /** 启用/停用权限 */
    void setPermStatus(long id, long actorId, int status);

    /** 按 ID 查询未删除的权限 */
    RbacPermModel getPerm(long id);

    /** 分页查询权限（关键字、类型、状态筛选） */
    PageResponse<RbacPermModel> listPerms(RbacPermQuery query);

    // —— 权限组 ——

    /** 新建权限组 */
    RbacPermGroupModel createPermGroup(CreatePermGroupInput input);

    /** 更新权限组；不可改 code */
    RbacPermGroupModel updatePermGroup(UpdatePermGroupInput input);

    /** 启用/停用权限组 */
    void setPermGroupStatus(long id, long actorId, int status);

    /** 查询权限组详情（含已绑定的权限 ID 列表） */
    RbacPermGroupModel getPermGroup(long id);

    /** 分页查询权限组 */
    PageResponse<RbacPermGroupModel> listPermGroups(RbacPermGroupQuery query);

    /** 覆盖权限组下的权限列表（先删后插） */
    void bindGroupPerms(long groupId, List<Long> permIds, long actorId);

    // —— 角色 ——

    /** 新建角色 */
    RbacRoleModel createRole(CreateRoleInput input);

    /** 更新角色；不可改 code */
    RbacRoleModel updateRole(UpdateRoleInput input);

    /** 启用/停用角色；内置角色不可停用 */
    void setRoleStatus(long id, long actorId, int status);

    /**
     * 物理删除角色（编码可复用）；连带解除该角色的权限组与用户绑定；
     * 内置角色不可删除
     */
    void deleteRole(long id, long actorId);

    /** 查询角色详情（含已绑定的权限组 ID 列表） */
    RbacRoleModel getRole(long id);

    /** 分页查询角色 */
    PageResponse<RbacRoleModel> listRoles(RbacRoleQuery query);

    /** 覆盖角色绑定的权限组（先删后插） */
    void bindRoleGroups(long roleId, List<Long> groupIds, long actorId);

    // —— 用户与运维 ——

    /** 覆盖用户角色；含超管授予/自解绑校验 */
    void bindUserRoles(BindUserRolesInput input);

    /** 批量查询各用户当前绑定的未删除角色 */
    Map<Long, List<RbacRoleModel>> listRolesByUserIds(List<Long> userIds);

    /** 当前持有启用超管角色的用户 ID 列表 */
    List<Long> listUserIdsWithEnabledSuperAdmin();

    /** 将内置超管角色标记为不可变（运维用，防误删/误禁） */
    void lockSuperAdminRole();

}
