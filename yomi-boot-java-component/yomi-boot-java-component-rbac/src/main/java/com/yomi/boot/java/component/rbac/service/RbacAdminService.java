package com.yomi.boot.java.component.rbac.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.rbac.IRbacAdminService;
import com.yomi.boot.java.component.rbac.convert.RbacModelConverter;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacPermType;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import com.yomi.boot.java.component.rbac.exception.RbacBiz;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.rbac.input.*;
import com.yomi.boot.java.component.rbac.model.*;
import com.yomi.boot.java.component.rbac.repository.*;
import com.yomi.boot.java.component.rbac.support.RbacSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * RBAC 管理面默认实现
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RbacAdminService implements IRbacAdminService {

    /** 权限 CRUD */
    private final IRbacPermRepository permRepository;

    /** 权限组 CRUD */
    private final IRbacPermGroupRepository permGroupRepository;

    /** 角色 CRUD */
    private final IRbacRoleRepository roleRepository;

    /** 用户-角色、角色-组、组-权限关系 */
    private final IRbacRelationRepository relationRepository;

    /** 授权查询（超管判定等） */
    private final IRbacAuthRepository authRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RbacPermModel createPerm(CreatePermInput input) {
        if (input == null) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        String code = RbacSupport.normalizeCode(input.getCode());
        String name = StringUtils.trimToEmpty(input.getName());
        RbacPermType type = RbacPermType.of(input.getType());
        if (code.isEmpty() || name.isEmpty() || type == null) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        if (permRepository.findByCode(code) != null) {
            throw RbacBiz.of(RbacErrorCode.CODE_CONFLICT);
        }
        RbacPerm perm = new RbacPerm();
        perm.init();
        perm.setCode(code);
        perm.setName(name);
        perm.setType(type.getCode());
        perm.setRemark(StringUtils.trimToEmpty(input.getRemark()));
        perm.setStatus(RbacStatus.ACTIVE.getCode());
        fillPermFields(perm, type, input.getHttpMethod(), input.getHttpPath(), input.getUiPath(), 0L);
        perm.setCreateBy(input.getActorId() > 0 ? input.getActorId() : null);
        perm.setUpdateBy(perm.getCreateBy());
        try {
            permRepository.save(perm);
        } catch (DuplicateKeyException e) {
            // 并发登记或历史数据占用（如软删遗留行）：直接抛出编码冲突错误
            throw RbacBiz.of(RbacErrorCode.CODE_CONFLICT);
        }
        return RbacModelConverter.toPermModel(perm);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RbacPermModel updatePerm(UpdatePermInput input) {
        if (input == null || input.getId() <= 0) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        RbacPerm perm = permRepository.findActive(input.getId());
        if (perm == null || !perm.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_NOT_FOUND);
        }
        if (StringUtils.isNotBlank(input.getName())) {
            perm.setName(input.getName().trim());
        }
        perm.setRemark(StringUtils.trimToEmpty(input.getRemark()));
        RbacPermType type = RbacPermType.of(perm.getType());
        if (type == RbacPermType.API) {
            if (StringUtils.isNotBlank(input.getHttpMethod())) {
                perm.setHttpMethod(StringUtils.upperCase(input.getHttpMethod().trim()));
            }
            if (StringUtils.isNotBlank(input.getHttpPath())) {
                perm.setHttpPath(input.getHttpPath().trim());
            }
            if (permRepository.existsApiKey(perm.getHttpMethod(), perm.getHttpPath(), perm.getId())) {
                throw RbacBiz.of(RbacErrorCode.API_KEY_CONFLICT);
            }
        } else if (type == RbacPermType.MENU) {
            if (StringUtils.isNotBlank(input.getUiPath())) {
                perm.setUiPath(input.getUiPath().trim());
            }
            if (permRepository.existsMenuPath(perm.getUiPath(), perm.getId())) {
                throw RbacBiz.of(RbacErrorCode.MENU_PATH_CONFLICT);
            }
        }
        perm.update();
        perm.setUpdateBy(input.getActorId() > 0 ? input.getActorId() : null);
        permRepository.update(perm);
        RbacPerm updatedPerm = permRepository.findActive(perm.getId());
        return RbacModelConverter.toPermModel(updatedPerm);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setPermStatus(long id, long actorId, int status) {
        RbacPerm perm = permRepository.findActive(id);
        if (perm == null || !perm.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_NOT_FOUND);
        }
        perm.setStatus(RbacSupport.statusOrDefault(status));
        perm.update();
        perm.setUpdateBy(actorId > 0 ? actorId : null);
        permRepository.update(perm);
    }

    /** 按 id 查权限点 */
    @Override
    public RbacPermModel getPerm(long id) {
        RbacPerm perm = permRepository.findActive(id);
        if (perm == null || !perm.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_NOT_FOUND);
        }
        return RbacModelConverter.toPermModel(perm);
    }

    /** 条件分页列出权限点 */
    @Override
    public PageResponse<RbacPermModel> listPerms(RbacPermQuery query) {
        RbacPermQuery q = query == null ? new RbacPermQuery() : query;
        Page<RbacPerm> page = permRepository.pageList(q.getPageNo(), q.getPageSize(),
                q.getKeyword(), q.getType(), q.getStatus());
        return PageResponse.page(page.getRecords().stream().map(RbacModelConverter::toPermModel).toList(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RbacPermGroupModel createPermGroup(CreatePermGroupInput input) {
        if (input == null) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        String code = RbacSupport.normalizeCode(input.getCode());
        String name = StringUtils.trimToEmpty(input.getName());
        if (code.isEmpty() || name.isEmpty()) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        if (permGroupRepository.findByCode(code) != null) {
            throw RbacBiz.of(RbacErrorCode.CODE_CONFLICT);
        }
        RbacPermGroup group = new RbacPermGroup();
        group.init();
        group.setCode(code);
        group.setName(name);
        group.setRemark(StringUtils.trimToEmpty(input.getRemark()));
        group.setStatus(RbacStatus.ACTIVE.getCode());
        group.setCreateBy(input.getActorId() > 0 ? input.getActorId() : null);
        group.setUpdateBy(group.getCreateBy());
        try {
            permGroupRepository.save(group);
        } catch (DuplicateKeyException e) {
            throw RbacBiz.of(RbacErrorCode.CODE_CONFLICT);
        }
        return RbacModelConverter.toPermGroupModel(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RbacPermGroupModel updatePermGroup(UpdatePermGroupInput input) {
        if (input == null || input.getId() <= 0) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        RbacPermGroup group = permGroupRepository.findActive(input.getId());
        if (group == null || !group.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_GROUP_NOT_FOUND);
        }
        if (StringUtils.isNotBlank(input.getName())) {
            group.setName(input.getName().trim());
        }
        group.setRemark(StringUtils.trimToEmpty(input.getRemark()));
        group.update();
        group.setUpdateBy(input.getActorId() > 0 ? input.getActorId() : null);
        permGroupRepository.update(group);
        RbacPermGroup updatedGroup = permGroupRepository.findActive(group.getId());
        return RbacModelConverter.toPermGroupModel(updatedGroup);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setPermGroupStatus(long id, long actorId, int status) {
        RbacPermGroup group = permGroupRepository.findActive(id);
        if (group == null || !group.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_GROUP_NOT_FOUND);
        }
        group.setStatus(RbacSupport.statusOrDefault(status));
        group.update();
        group.setUpdateBy(actorId > 0 ? actorId : null);
        permGroupRepository.update(group);
    }

    /** 按 id 查权限组 */
    @Override
    public RbacPermGroupModel getPermGroup(long id) {
        RbacPermGroup group = permGroupRepository.findActive(id);
        if (group == null || !group.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_GROUP_NOT_FOUND);
        }
        RbacPermGroupModel model = RbacModelConverter.toPermGroupModel(group);
        model.setPermIds(relationRepository.listPermIdsByGroupId(id));
        return model;
    }

    /** 条件分页列出权限组 */
    @Override
    public PageResponse<RbacPermGroupModel> listPermGroups(RbacPermGroupQuery query) {
        RbacPermGroupQuery q = query == null ? new RbacPermGroupQuery() : query;
        Page<RbacPermGroup> page = permGroupRepository.pageList(q.getPageNo(), q.getPageSize(),
                q.getKeyword(), q.getStatus());
        return PageResponse.page(page.getRecords().stream().map(RbacModelConverter::toPermGroupModel).toList(),
                page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindGroupPerms(long groupId, List<Long> permIds, long actorId) {
        RbacPermGroup group = permGroupRepository.findActive(groupId);
        if (group == null || !group.exist()) {
            throw RbacBiz.of(RbacErrorCode.PERM_GROUP_NOT_FOUND);
        }
        List<Long> ids = RbacSupport.uniqueIds(permIds);
        // 一次批量校验存在性：逐条查库会随绑定条数线性放大
        if (permRepository.listActiveByIds(ids).size() != ids.size()) {
            throw RbacBiz.of(RbacErrorCode.PERM_NOT_FOUND);
        }
        relationRepository.replaceGroupPerms(groupId, ids, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RbacRoleModel createRole(CreateRoleInput input) {
        if (input == null) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        String code = RbacSupport.normalizeCode(input.getCode());
        String name = StringUtils.trimToEmpty(input.getName());
        if (code.isEmpty() || name.isEmpty()) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        if (RbacBuiltinRole.SUPER_ADMIN.equals(code)) {
            throw RbacBiz.of(RbacErrorCode.BUILTIN_IMMUTABLE);
        }
        if (roleRepository.findByCode(code) != null) {
            throw RbacBiz.of(RbacErrorCode.CODE_CONFLICT);
        }
        RbacRole role = new RbacRole();
        role.init();
        role.setCode(code);
        role.setName(name);
        role.setBuiltin(0);
        role.setRemark(StringUtils.trimToEmpty(input.getRemark()));
        role.setStatus(RbacStatus.ACTIVE.getCode());
        role.setCreateBy(input.getActorId() > 0 ? input.getActorId() : null);
        role.setUpdateBy(role.getCreateBy());
        try {
            roleRepository.save(role);
        } catch (DuplicateKeyException e) {
            // 并发创建，或历史软删行仍占用唯一键（存量库）：直接抛出编码冲突错误
            throw RbacBiz.of(RbacErrorCode.CODE_CONFLICT);
        }
        return RbacModelConverter.toRoleModel(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RbacRoleModel updateRole(UpdateRoleInput input) {
        if (input == null || input.getId() <= 0) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        RbacRole role = roleRepository.findActive(input.getId());
        if (role == null || !role.exist()) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND);
        }
        if (StringUtils.isNotBlank(input.getName())) {
            role.setName(input.getName().trim());
        }
        role.setRemark(StringUtils.trimToEmpty(input.getRemark()));
        role.update();
        role.setUpdateBy(input.getActorId() > 0 ? input.getActorId() : null);
        roleRepository.update(role);
        RbacRole updatedRole = roleRepository.findActive(role.getId());
        return RbacModelConverter.toRoleModel(updatedRole);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setRoleStatus(long id, long actorId, int status) {
        RbacRole role = roleRepository.findActive(id);
        if (role == null || !role.exist()) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND);
        }
        int target = RbacSupport.statusOrDefault(status);
        if (RbacSupport.isBuiltinRole(role)) {
            if (RbacStatus.DISABLED.getCode() == target) {
                throw RbacBiz.of(RbacErrorCode.BUILTIN_IMMUTABLE, "内置角色不可禁用");
            }
            // 内置角色须保持启用：重复启用请求幂等；若被禁用则恢复并告警
            if (RbacStatus.ACTIVE.getCode() == RbacSupport.statusOrDefault(role.getStatus())) {
                log.debug("[RBAC]内置角色已启用，忽略启用请求 roleId={}, code={}", id, role.getCode());
                return;
            }
            log.warn("[RBAC]发现内置角色处于禁用状态，已恢复为启用 roleId={}, code={}", id, role.getCode());
            role.setStatus(target);
            role.update();
            role.setUpdateBy(actorId > 0 ? actorId : null);
            roleRepository.update(role);
            return;
        }
        role.setStatus(target);
        role.update();
        role.setUpdateBy(actorId > 0 ? actorId : null);
        roleRepository.update(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(long id, long actorId) {
        RbacRole role = roleRepository.findActive(id);
        if (role == null || !role.exist()) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND);
        }
        if (RbacSupport.isBuiltinRole(role)) {
            throw RbacBiz.of(RbacErrorCode.BUILTIN_IMMUTABLE, "内置角色不可删除");
        }
        // 物理删除（RBAC 约定）：连带解除该角色的权限组与用户绑定，编码可复用；
        // 审计信息（操作人/时间）以日志留存
        relationRepository.deleteRoleRelations(id);
        roleRepository.del(id);
        log.info("[RBAC]角色已物理删除 roleId={}, code={}, actorId={}", id, role.getCode(), actorId);
    }

    /** 按 id 查角色 */
    @Override
    public RbacRoleModel getRole(long id) {
        RbacRole role = roleRepository.findActive(id);
        if (role == null || !role.exist()) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND);
        }
        RbacRoleModel model = RbacModelConverter.toRoleModel(role);
        model.setGroupIds(relationRepository.listGroupIdsByRoleId(id));
        return model;
    }

    /** 条件分页列出角色 */
    @Override
    public PageResponse<RbacRoleModel> listRoles(RbacRoleQuery query) {
        RbacRoleQuery q = query == null ? new RbacRoleQuery() : query;
        Page<RbacRole> page = roleRepository.pageList(q.getPageNo(), q.getPageSize(),
                q.getKeyword(), q.getStatus());
        return PageResponse.page(page.getRecords().stream().map(RbacModelConverter::toRoleModel).toList(),
                page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindRoleGroups(long roleId, List<Long> groupIds, long actorId) {
        RbacRole role = roleRepository.findActive(roleId);
        if (role == null || !role.exist()) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND);
        }
        List<Long> ids = RbacSupport.uniqueIds(groupIds);
        // 一次批量校验存在性：逐条查库会随绑定条数线性放大
        if (permGroupRepository.listActiveByIds(ids).size() != ids.size()) {
            throw RbacBiz.of(RbacErrorCode.PERM_GROUP_NOT_FOUND);
        }
        relationRepository.replaceRoleGroups(roleId, ids, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindUserRoles(BindUserRolesInput input) {
        if (input == null || input.getUserId() <= 0) {
            throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
        }
        List<Long> roleIds = RbacSupport.uniqueIds(input.getRoleIds());
        // 一次批量取回角色：既做存在性校验，也在内存里判超管
        List<RbacRole> roles = roleRepository.listActiveByIds(roleIds);
        if (roles.size() != roleIds.size()) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND);
        }
        boolean willHaveSuper = roles.stream().anyMatch(role -> RbacSupport.isSuperRole(role)
                && RbacStatus.ACTIVE.getCode() == RbacSupport.statusOrDefault(role.getStatus()));
        boolean actorHasSuper = authRepository.userHasEnabledSuperAdmin(input.getActorId());
        if (willHaveSuper && !actorHasSuper) {
            throw RbacBiz.of(RbacErrorCode.GRANT_SUPER_DENIED);
        }
        if (input.getActorId() == input.getUserId()) {
            boolean hadSuper = authRepository.userHasEnabledSuperAdmin(input.getUserId());
            if (hadSuper && !willHaveSuper) {
                throw RbacBiz.of(RbacErrorCode.UNBIND_SELF_SUPER);
            }
        }
        relationRepository.replaceUserRoles(input.getUserId(), roleIds, input.getActorId());
    }

    /** 批量查用户已分配角色 */
    @Override
    public Map<Long, List<RbacRoleModel>> listRolesByUserIds(List<Long> userIds) {
        Map<Long, List<RbacRoleModel>> mapped = new LinkedHashMap<>();
        relationRepository.listRolesByUserIds(userIds)
                .forEach((userId, roles) -> mapped.put(userId, roles.stream().map(RbacModelConverter::toRoleModel).toList()));
        return mapped;
    }

    /** 列出启用超管角色的用户 id */
    @Override
    public List<Long> listUserIdsWithEnabledSuperAdmin() {
        return authRepository.listUserIdsWithEnabledSuperAdmin();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockSuperAdminRole() {
        RbacRole role = roleRepository.lockSuperAdminRole();
        if (role == null) {
            throw RbacBiz.of(RbacErrorCode.ROLE_NOT_FOUND, "超管角色不存在");
        }
    }

    /** 按类型填充权限 HTTP/菜单字段 */
    private void fillPermFields(RbacPerm perm, RbacPermType type, String httpMethod, String httpPath, String uiPath, long excludeId) {
        if (type == RbacPermType.API) {
            String method = StringUtils.upperCase(StringUtils.trimToEmpty(httpMethod));
            String path = StringUtils.trimToEmpty(httpPath);
            if (method.isEmpty() || path.isEmpty()) {
                throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
            }
            if (permRepository.existsApiKey(method, path, excludeId > 0 ? excludeId : null)) {
                throw RbacBiz.of(RbacErrorCode.API_KEY_CONFLICT);
            }
            perm.setHttpMethod(method);
            perm.setHttpPath(path);
        } else if (type == RbacPermType.MENU) {
            String path = StringUtils.trimToEmpty(uiPath);
            if (path.isEmpty()) {
                throw RbacBiz.of(RbacErrorCode.INVALID_INPUT);
            }
            if (permRepository.existsMenuPath(path, excludeId > 0 ? excludeId : null)) {
                throw RbacBiz.of(RbacErrorCode.MENU_PATH_CONFLICT);
            }
            perm.setUiPath(path);
        }
    }

}
