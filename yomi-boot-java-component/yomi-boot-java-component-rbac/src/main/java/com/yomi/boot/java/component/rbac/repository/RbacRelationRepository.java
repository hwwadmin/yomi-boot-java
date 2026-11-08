package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.rbac.entity.RbacGroupPermRel;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.entity.RbacRoleGroupRel;
import com.yomi.boot.java.component.rbac.entity.RbacUserRoleRel;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacGroupPermRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleGroupRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacUserRoleRelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关系表读写（物理覆盖）；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 * <p>"先删后插"须原子，故整类带 REQUIRED 事务：组件内服务方法自带事务时汇入，
 * 宿主直接调端口时也能自成事务，绑定替换要么全部生效要么全部回滚
 *
 * @author zwei
 */
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class RbacRelationRepository implements IRbacRelationRepository {

    /** 用户-角色 */
    private final RbacUserRoleRelMapper userRoleRelMapper;

    /** 角色-权限组 */
    private final RbacRoleGroupRelMapper roleGroupRelMapper;

    /** 权限组-权限关系 */
    private final RbacGroupPermRelMapper groupPermRelMapper;

    /** 加载角色实体（过滤已删除） */
    private final IRbacRoleRepository roleRepository;

    /** 用户绑定的角色 ID 列表（有序） */
    public List<Long> listRoleIdsByUserId(long userId) {
        return userRoleRelMapper.selectList(new LambdaQueryWrapper<RbacUserRoleRel>()
                        .eq(RbacUserRoleRel::getUserId, userId)
                        .orderByAsc(RbacUserRoleRel::getId))
                .stream().map(RbacUserRoleRel::getRoleId).toList();
    }

    /** 角色绑定的权限组 ID 列表（有序） */
    public List<Long> listGroupIdsByRoleId(long roleId) {
        return roleGroupRelMapper.selectList(new LambdaQueryWrapper<RbacRoleGroupRel>()
                        .eq(RbacRoleGroupRel::getRoleId, roleId)
                        .orderByAsc(RbacRoleGroupRel::getId))
                .stream().map(RbacRoleGroupRel::getGroupId).toList();
    }

    /** 权限组绑定的权限 ID 列表（有序） */
    public List<Long> listPermIdsByGroupId(long groupId) {
        return groupPermRelMapper.selectList(new LambdaQueryWrapper<RbacGroupPermRel>()
                        .eq(RbacGroupPermRel::getGroupId, groupId)
                        .orderByAsc(RbacGroupPermRel::getId))
                .stream().map(RbacGroupPermRel::getPermId).toList();
    }

    /** 先删后插，覆盖用户角色 */
    public void replaceUserRoles(long userId, List<Long> roleIds, long actorId) {
        userRoleRelMapper.delete(new LambdaQueryWrapper<RbacUserRoleRel>().eq(RbacUserRoleRel::getUserId, userId));
        List<Long> ids = roleIds == null ? List.of() : roleIds;
        if (ids.isEmpty()) {
            return;
        }
        List<RbacUserRoleRel> rows = new ArrayList<>(ids.size());
        for (Long roleId : ids) {
            RbacUserRoleRel row = new RbacUserRoleRel();
            row.setId(IdUtils.nextId());
            Instant now = Instant.now();
            row.setCreateTime(now);
            row.setUpdateTime(now);
            row.setUserId(userId);
            row.setRoleId(roleId);
            row.setCreateBy(actorId > 0 ? actorId : null);
            row.setUpdateBy(actorId > 0 ? actorId : null);
            rows.add(row);
        }
        userRoleRelMapper.insert(rows);
    }

    /** 先删后插，覆盖角色权限组 */
    public void replaceRoleGroups(long roleId, List<Long> groupIds, long actorId) {
        roleGroupRelMapper.delete(new LambdaQueryWrapper<RbacRoleGroupRel>().eq(RbacRoleGroupRel::getRoleId, roleId));
        List<Long> ids = groupIds == null ? List.of() : groupIds;
        if (ids.isEmpty()) {
            return;
        }
        List<RbacRoleGroupRel> rows = new ArrayList<>(ids.size());
        for (Long groupId : ids) {
            RbacRoleGroupRel row = new RbacRoleGroupRel();
            row.setId(IdUtils.nextId());
            Instant now = Instant.now();
            row.setCreateTime(now);
            row.setUpdateTime(now);
            row.setRoleId(roleId);
            row.setGroupId(groupId);
            row.setCreateBy(actorId > 0 ? actorId : null);
            row.setUpdateBy(actorId > 0 ? actorId : null);
            rows.add(row);
        }
        roleGroupRelMapper.insert(rows);
    }

    /** 先删后插，覆盖组内权限 */
    public void replaceGroupPerms(long groupId, List<Long> permIds, long actorId) {
        groupPermRelMapper.delete(new LambdaQueryWrapper<RbacGroupPermRel>().eq(RbacGroupPermRel::getGroupId, groupId));
        List<Long> ids = permIds == null ? List.of() : permIds;
        if (ids.isEmpty()) {
            return;
        }
        List<RbacGroupPermRel> rows = new ArrayList<>(ids.size());
        for (Long permId : ids) {
            RbacGroupPermRel row = new RbacGroupPermRel();
            row.setId(IdUtils.nextId());
            Instant now = Instant.now();
            row.setCreateTime(now);
            row.setUpdateTime(now);
            row.setGroupId(groupId);
            row.setPermId(permId);
            row.setCreateBy(actorId > 0 ? actorId : null);
            row.setUpdateBy(actorId > 0 ? actorId : null);
            rows.add(row);
        }
        groupPermRelMapper.insert(rows);
    }

    /** 物理删除角色的全部绑定：角色-权限组、用户-角色（与 deleteRole 同事务调用） */
    public void deleteRoleRelations(long roleId) {
        roleGroupRelMapper.delete(new LambdaQueryWrapper<RbacRoleGroupRel>().eq(RbacRoleGroupRel::getRoleId, roleId));
        userRoleRelMapper.delete(new LambdaQueryWrapper<RbacUserRoleRel>().eq(RbacUserRoleRel::getRoleId, roleId));
    }

    /** 批量查询用户 → 未删除角色列表（无绑定的用户对应空列表） */
    public Map<Long, List<RbacRole>> listRolesByUserIds(List<Long> userIds) {
        Map<Long, List<RbacRole>> out = new HashMap<>();
        // 空入参直接返回空结果
        if (userIds == null || userIds.isEmpty()) {
            return out;
        }
        for (Long uid : userIds) {
            out.put(uid, new ArrayList<>());
        }
        List<RbacUserRoleRel> rels = userRoleRelMapper.selectList(new LambdaQueryWrapper<RbacUserRoleRel>()
                .in(RbacUserRoleRel::getUserId, userIds));
        // 角色一次批量取回：逐角色查库会随"涉及的角色数 + 引用已软删角色的关系行数"线性放大
        Map<Long, RbacRole> roleById = roleRepository.listActiveByIds(
                        rels.stream().map(RbacUserRoleRel::getRoleId).distinct().toList())
                .stream().collect(Collectors.toMap(RbacRole::getId, Function.identity(), (a, b) -> a));
        for (RbacUserRoleRel rel : rels) {
            RbacRole role = roleById.get(rel.getRoleId());
            if (role != null) {
                out.computeIfAbsent(rel.getUserId(), k -> new ArrayList<>()).add(role);
            }
        }
        return out;
    }

}
