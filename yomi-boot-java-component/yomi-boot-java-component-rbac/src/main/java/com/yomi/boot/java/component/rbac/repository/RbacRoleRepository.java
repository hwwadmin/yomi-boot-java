package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleMapper;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * t_sys_rbac_role 数据访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
public class RbacRoleRepository implements IRbacRoleRepository {

    private final RbacRoleMapper mapper;

    public RbacRoleRepository(RbacRoleMapper mapper) {
        this.mapper = mapper;
    }

    /** lambda 链式条件 */
    private LambdaQueryChainWrapper<RbacRole> query() {
        return ChainWrappers.lambdaQueryChain(mapper, RbacRole.class);
    }

    /** 新增角色 */
    public void save(RbacRole row) {
        Assert.nonNull(row, "table#RbacRole不能为空");
        Instant now = Instant.now();
        if (row.getId() == null) {
            row.setId(IdUtils.nextId());
        }
        if (row.getCreateTime() == null) {
            row.setCreateTime(now);
        }
        row.setUpdateTime(now);
        mapper.insert(row);
    }

    /** 按主键更新角色 */
    public void update(RbacRole row) {
        Assert.nonNull(row, "table#RbacRole不能为空");
        Assert.nonNull(row.getId(), "table#RbacRole ID不能为空");
        row.setUpdateTime(Instant.now());
        int rows = mapper.updateById(row);
        Assert.isTrue(rows == 1, "table#RbacRole更新失败，ID[{}]不存在", row.getId());
    }

    /** 按主键物理删除角色（连带清理关系由服务层编排） */
    public void del(Long id) {
        Assert.nonNull(id, "table#RbacRole ID不能为空");
        mapper.deleteById(id);
    }

    /** 按 ID 查询未删除的角色 */
    public RbacRole findActive(Long id) {
        if (id == null) {
            return null;
        }
        RbacRole entity = mapper.selectById(id);
        return entity != null && entity.getDeleteTime() == null ? entity : null;
    }

    /** 按主键批量查询未删除的角色（一次查库，不随角色数增长） */
    public List<RbacRole> listActiveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectByIds(ids).stream().filter(row -> row.getDeleteTime() == null).toList();
    }

    /** 按编码查询未删除的角色 */
    public RbacRole findByCode(String code) {
        return query().eq(RbacRole::getCode, code).isNull(RbacRole::getDeleteTime).one();
    }

    /** 行锁内置超管角色（同事务内更新 builtin 标记） */
    public RbacRole lockSuperAdminRole() {
        return mapper.lockSuperAdminRoleForUpdate(RbacBuiltinRole.SUPER_ADMIN);
    }

    /** 分页列表；page 最小 1，size 默认 20、上限 100 */
    public Page<RbacRole> pageList(int page, int size, String keyword, Integer status) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
        var q = query().isNull(RbacRole::getDeleteTime);
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
