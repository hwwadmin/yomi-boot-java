package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;

import java.util.List;

/**
 * 鉴权查询仓储（端口）：鉴权链 SQL 的数据访问入口
 * <p>
 * 组件内其余代码（鉴权服务/快照/投影）都经本接口读写数据，Mapper 只由默认实现调用；
 * 默认实现 {@link RbacAuthRepository}，宿主可实现本接口并注册自己的 Bean。
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
