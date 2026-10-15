package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;

import java.util.List;

/**
 * 鉴权查询仓储（端口）：鉴权链 SQL 的持久化唯一出入口
 * <p>
 * 组件内其余代码（鉴权服务/快照/投影）一律经本接口访问，**不得越过仓储直接调用 Mapper**；
 * 默认实现 {@link RbacAuthRepository}，宿主可自行实现本接口并注册 Bean 顶替。
 *
 * @author zwei
 */
public interface IRbacAuthRepository {

    /** 用户是否拥有启用状态的内置超管角色 */
    boolean userHasEnabledSuperAdmin(long userId);

    /** 是否存在匹配的启用 API 权限（方法 + 路径） */
    boolean existsHttpAccess(long userId, String method, String path);

    /** 用户的启用菜单权限列表 */
    List<RbacPerm> listMenuByUserId(long userId);

    /** 全部启用菜单权限 */
    List<RbacPerm> listEnabledMenus();

    /** 拥有启用超管角色的用户 ID 列表 */
    List<Long> listUserIdsWithEnabledSuperAdmin();

}
