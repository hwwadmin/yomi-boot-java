package com.yomi.boot.java.component.rbac.cache;

import lombok.Getter;

/**
 * RBAC 投影 Redis 键
 *
 * @author zwei
 */
@Getter
public class RbacProjectionKeys {

    /** Redis 投影缓存键前缀 */
    private final String prefix;

    public RbacProjectionKeys(String prefix) {
        this.prefix = prefix.endsWith(":") ? prefix : prefix + ":";
    }

    /** 当前生效投影版本指针键 */
    public String currentKey() {
        return prefix + "rbac:cache:current";
    }

    /** 全量刷新 / 懒回源互斥锁键 */
    public String refreshLockKey() {
        return prefix + "rbac:cache:refresh";
    }

    /** 指定版本的权限实体 Hash 键 */
    public String permHashKey(String version) {
        return prefix + "rbac:cache:" + version + ":perm";
    }

    /** 指定版本的权限组实体 Hash 键 */
    public String groupHashKey(String version) {
        return prefix + "rbac:cache:" + version + ":group";
    }

    /** 指定版本的角色实体 Hash 键 */
    public String roleHashKey(String version) {
        return prefix + "rbac:cache:" + version + ":role";
    }

    /** 指定版本的用户→角色关系 Hash 键 */
    public String userRoleHashKey(String version) {
        return prefix + "rbac:cache:" + version + ":user_role";
    }

    /** 指定版本的角色→权限组关系 Hash 键 */
    public String roleGroupHashKey(String version) {
        return prefix + "rbac:cache:" + version + ":role_group";
    }

    /** 指定版本的权限组→权限关系 Hash 键 */
    public String groupPermHashKey(String version) {
        return prefix + "rbac:cache:" + version + ":group_perm";
    }

}
