package com.yomi.boot.java.component.rbac.snapshot;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 六表投影内存形态（Redis 投影与求值共用）
 *
 * @author zwei
 */
@Data
public class RbacSnapshot {

    /** 权限列表 */
    private List<RbacPerm> perms = new ArrayList<>();

    /** 权限组列表 */
    private List<RbacPermGroup> groups = new ArrayList<>();

    /** 角色列表 */
    private List<RbacRole> roles = new ArrayList<>();

    /** userId → roleId 列表 */
    private Map<Long, List<Long>> userRoles = new HashMap<>();

    /** roleId → groupId 列表 */
    private Map<Long, List<Long>> roleGroups = new HashMap<>();

    /** groupId → permId 列表 */
    private Map<Long, List<Long>> groupPerms = new HashMap<>();

    /** 空快照（关系图为空 map） */
    public static RbacSnapshot empty() {
        RbacSnapshot snap = new RbacSnapshot();
        snap.setUserRoles(new HashMap<>());
        snap.setRoleGroups(new HashMap<>());
        snap.setGroupPerms(new HashMap<>());
        return snap;
    }

}
