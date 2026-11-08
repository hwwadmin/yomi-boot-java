package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermGroupMapper;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * sys_rbac_perm_group 数据访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
public class RbacPermGroupRepository implements IRbacPermGroupRepository {

    private final RbacPermGroupMapper mapper;

    public RbacPermGroupRepository(RbacPermGroupMapper mapper) {
        this.mapper = mapper;
    }

    /** lambda 链式条件 */
    private LambdaQueryChainWrapper<RbacPermGroup> query() {
        return ChainWrappers.lambdaQueryChain(mapper, RbacPermGroup.class);
    }

    /** 新增权限组 */
    public void save(RbacPermGroup row) {
        Assert.nonNull(row, "table#RbacPermGroup不能为空");
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

    /** 按主键更新权限组 */
    public void update(RbacPermGroup row) {
        Assert.nonNull(row, "table#RbacPermGroup不能为空");
        Assert.nonNull(row.getId(), "table#RbacPermGroup ID不能为空");
        row.setUpdateTime(Instant.now());
        int rows = mapper.updateById(row);
        Assert.isTrue(rows == 1, "table#RbacPermGroup更新失败，ID[{}]不存在", row.getId());
    }

    /** 按 ID 查询未删除的权限组 */
    public RbacPermGroup findActive(Long id) {
        if (id == null) {
            return null;
        }
        RbacPermGroup entity = mapper.selectById(id);
        return entity != null && entity.getDeleteTime() == null ? entity : null;
    }

    /** 按主键批量查询未删除的权限组（一次查库，不随权限组数增长） */
    public List<RbacPermGroup> listActiveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectByIds(ids).stream().filter(row -> row.getDeleteTime() == null).toList();
    }

    /** 按编码查询未删除的权限组 */
    public RbacPermGroup findByCode(String code) {
        return query().eq(RbacPermGroup::getCode, code).isNull(RbacPermGroup::getDeleteTime).one();
    }

    /** 分页列表；page 最小 1，size 默认 20、上限 100 */
    public Page<RbacPermGroup> pageList(int page, int size, String keyword, Integer status) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
        var q = query().isNull(RbacPermGroup::getDeleteTime);
        if (StringUtils.isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            q.and(w -> w.like(RbacPermGroup::getCode, like).or().like(RbacPermGroup::getName, like));
        }
        if (status != null) {
            q.eq(RbacPermGroup::getStatus, status);
        }
        q.orderByAsc(RbacPermGroup::getId);
        return q.page(new Page<>(page, size));
    }

}
