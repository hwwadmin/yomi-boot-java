package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;

import java.util.Collection;
import java.util.List;

/**
 * 权限仓储（端口）：sys_rbac_perm 的数据访问入口
 * <p>
 * 组件内其余代码（管理服务/快照/投影）都经本接口读写数据，Mapper 只由默认实现调用；
 * 默认实现 {@link RbacPermRepository}，宿主可实现本接口并注册自己的 Bean。
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

    /** 按主键批量查未删除权限（一次查库；结果不含已软删行，顺序不保证，空入参返回空列表） */
    List<RbacPerm> listActiveByIds(Collection<Long> ids);

    /** 按编码查权限；不存在返回 null */
    RbacPerm findByCode(String code);

    /** 是否存在同方法+路径的 API 权限（排除指定 id） */
    boolean existsApiKey(String method, String path, Long excludeId);

    /** 是否存在同 UI 路径的菜单权限（排除指定 id） */
    boolean existsMenuPath(String uiPath, Long excludeId);

    /** 分页列表（关键字模糊 + 类型/状态过滤，条件可空） */
    Page<RbacPerm> pageList(int page, int size, String keyword, String type, Integer status);

}
