package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;

import java.util.Collection;
import java.util.List;

/**
 * 权限组仓储（端口）：t_sys_rbac_perm_group 的数据访问入口
 * <p>
 * 组件内其余代码（管理服务/快照/投影）都经本接口读写数据，Mapper 只由默认实现调用；
 * 默认实现 {@link RbacPermGroupRepository}，宿主可实现本接口并注册自己的 Bean。
 *
 * @author zwei
 */
public interface IRbacPermGroupRepository {

    /** 新增权限组 */
    void save(RbacPermGroup row);

    /** 按主键更新权限组 */
    void update(RbacPermGroup row);

    /** 查启用状态的权限组；不存在返回 null */
    RbacPermGroup findActive(Long id);

    /** 按主键批量查未删除权限组（一次查库；结果不含已软删行，顺序不保证，空入参返回空列表） */
    List<RbacPermGroup> listActiveByIds(Collection<Long> ids);

    /** 按编码查权限组；不存在返回 null */
    RbacPermGroup findByCode(String code);

    /** 分页列表（关键字模糊 + 状态过滤，条件可空） */
    Page<RbacPermGroup> pageList(int page, int size, String keyword, Integer status);

}
