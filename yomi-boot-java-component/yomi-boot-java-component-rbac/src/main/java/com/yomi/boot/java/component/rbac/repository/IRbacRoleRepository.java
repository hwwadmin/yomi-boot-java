package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.rbac.entity.RbacRole;

import java.util.Collection;
import java.util.List;

/**
 * 角色仓储（端口）：sys_rbac_role 的数据访问入口
 * <p>
 * 组件内其余代码（管理服务/快照/投影）都经本接口读写数据，Mapper 只由默认实现调用；
 * 默认实现 {@link RbacRoleRepository}，宿主可实现本接口并注册自己的 Bean。
 *
 * @author zwei
 */
public interface IRbacRoleRepository {

    /** 新增角色（自动 init 审计字段） */
    void save(RbacRole row);

    /** 按主键更新角色 */
    void update(RbacRole row);

    /** 按主键物理删除角色（连带清理关系由服务层编排） */
    void del(Long id);

    /** 查启用状态的角色；不存在返回 null */
    RbacRole findActive(Long id);

    /** 按主键批量查未删除角色（一次查库；结果不含已软删行，顺序不保证，空入参返回空列表） */
    List<RbacRole> listActiveByIds(Collection<Long> ids);

    /** 按编码查角色；不存在返回 null */
    RbacRole findByCode(String code);

    /** 锁定内置超管角色行（防并发变更），不存在返回 null */
    RbacRole lockSuperAdminRole();

    /** 分页列表（关键字模糊 + 状态过滤，条件可空） */
    Page<RbacRole> pageList(int page, int size, String keyword, Integer status);

}
