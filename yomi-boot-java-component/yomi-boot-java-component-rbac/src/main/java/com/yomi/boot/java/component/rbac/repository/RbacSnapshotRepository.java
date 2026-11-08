package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.entity.*;
import com.yomi.boot.java.component.rbac.repository.mapper.*;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 六表全量加载（投影 Refresh / Redis 回源）；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class RbacSnapshotRepository implements IRbacSnapshotRepository {

    /** 权限表 */
    private final RbacPermMapper permMapper;

    /** 权限组表 */
    private final RbacPermGroupMapper permGroupMapper;

    /** 角色表 */
    private final RbacRoleMapper roleMapper;

    /** 用户-角色关系 */
    private final RbacUserRoleRelMapper userRoleRelMapper;

    /** 角色-权限组关系 */
    private final RbacRoleGroupRelMapper roleGroupRelMapper;

    /** 权限组-权限关系 */
    private final RbacGroupPermRelMapper groupPermRelMapper;

    /** 从 DB 加载六表并组装关系图 */
    public RbacSnapshot loadAllForCache() {
        RbacSnapshot snap = new RbacSnapshot();
        snap.setPerms(permMapper.selectList(null).stream().filter(row -> row.getDeleteTime() == null).toList());
        snap.setGroups(permGroupMapper.selectList(null).stream().filter(row -> row.getDeleteTime() == null).toList());
        snap.setRoles(roleMapper.selectList(null).stream().filter(row -> row.getDeleteTime() == null).toList());

        Map<Long, List<Long>> userRoles = new HashMap<>();
        for (RbacUserRoleRel rel : userRoleRelMapper.selectList(null)) {
            userRoles.computeIfAbsent(rel.getUserId(), k -> new ArrayList<>()).add(rel.getRoleId());
        }
        Map<Long, List<Long>> roleGroups = new HashMap<>();
        for (RbacRoleGroupRel rel : roleGroupRelMapper.selectList(null)) {
            roleGroups.computeIfAbsent(rel.getRoleId(), k -> new ArrayList<>()).add(rel.getGroupId());
        }
        Map<Long, List<Long>> groupPerms = new HashMap<>();
        for (RbacGroupPermRel rel : groupPermRelMapper.selectList(null)) {
            groupPerms.computeIfAbsent(rel.getGroupId(), k -> new ArrayList<>()).add(rel.getPermId());
        }
        snap.setUserRoles(userRoles);
        snap.setRoleGroups(roleGroups);
        snap.setGroupPerms(groupPerms);
        return snap;
    }

}
