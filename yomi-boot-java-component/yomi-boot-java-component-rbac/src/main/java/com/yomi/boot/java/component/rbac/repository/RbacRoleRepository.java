package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.List;

/**
 * sys_rbac_role 数据访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
public class RbacRoleRepository extends MyBatisDao<RbacRoleMapper, RbacRole>
        implements IRbacRoleRepository {

    public RbacRoleRepository(RbacRoleMapper mapper) {
        super(mapper);
    }

    /** 按 ID 查询未删除的角色 */
    public RbacRole findActive(Long id) {
        if (id == null) {
            return null;
        }
        RbacRole entity = getMapper().selectById(id);
        return entity != null && entity.exist() ? entity : null;
    }

    /** 按主键批量查询未删除的角色（一次查库，不随角色数增长） */
    public List<RbacRole> listActiveByIds(Collection<Long> ids) {
        return listByIds(ids);
    }

    /** 按编码查询未删除的角色 */
    public RbacRole findByCode(String code) {
        return lambdaQuery().eq(RbacRole::getCode, code).isNull(RbacRole::getDeleteTime).one();
    }

    /** 行锁内置超管角色（同事务内更新 builtin 标记） */
    public RbacRole lockSuperAdminRole() {
        return getMapper().lockSuperAdminRoleForUpdate(RbacBuiltinRole.SUPER_ADMIN);
    }

    /** 分页列表；page 最小 1，size 默认 20、上限 100 */
    public Page<RbacRole> pageList(int page, int size, String keyword, Integer status) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
        var q = lambdaQuery().isNull(RbacRole::getDeleteTime);
        if (StringUtils.isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            q.and(w -> w.like(RbacRole::getCode, like).or().like(RbacRole::getName, like));
        }
        if (status != null) {
            q.eq(RbacRole::getStatus, status);
        }
        q.orderByAsc(RbacRole::getId);
        return q.page(new Page<>(page, size));
    }

}
