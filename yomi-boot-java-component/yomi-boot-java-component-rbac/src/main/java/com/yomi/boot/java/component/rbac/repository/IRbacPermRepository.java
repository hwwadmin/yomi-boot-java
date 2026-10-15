package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;

/**
 * 权限仓储（端口）：sys_rbac_perm 的持久化唯一出入口
 * <p>
 * 组件内其余代码（管理服务/快照/投影）一律经本接口访问，**不得越过仓储直接调用 Mapper**；
 * 默认实现 {@link RbacPermRepository}，宿主可自行实现本接口并注册 Bean 顶替。
 *
 * @author zwei
 */
public interface IRbacPermRepository {

    /** 新增权限（自动 init 审计字段） */
    void save(RbacPerm row);

    /** 按主键更新权限 */
    void update(RbacPerm row);

    /** 查启用状态的权限；不存在返回 null */
    RbacPerm findActive(Long id);

    /** 按编码查权限；不存在返回 null */
    RbacPerm findByCode(String code);

    /** 是否存在同方法+路径的 API 权限（排除指定 id） */
    boolean existsApiKey(String method, String path, Long excludeId);

    /** 是否存在同 UI 路径的菜单权限（排除指定 id） */
    boolean existsMenuPath(String uiPath, Long excludeId);

    /** 分页列表（关键字模糊 + 类型/状态过滤，条件可空） */
    Page<RbacPerm> pageList(int page, int size, String keyword, String type, Integer status);

}
