package com.yomi.boot.java.component.rbac;

import com.yomi.boot.java.component.rbac.model.RbacPermModel;

import java.util.List;

/**
 * 授权查询（热路径）
 *
 * @author zwei
 */
public interface IRbacAuthService {

    /**
     * 判断用户是否可访问 HTTP；method 大写，path 为后端收到的 URI（不含 query）
     */
    boolean canAccessHttp(long userId, String method, String path);

    /** 列出用户可见的启用目录权限；超管返回全部启用 menu */
    List<RbacPermModel> listUiMenus(long userId);

    /** 全量刷新鉴权 Redis 投影 */
    void refreshProjection();

}
