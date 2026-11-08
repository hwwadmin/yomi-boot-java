package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermMapper;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * sys_rbac_perm 数据访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
public class RbacPermRepository implements IRbacPermRepository {

    private final RbacPermMapper mapper;

    public RbacPermRepository(RbacPermMapper mapper) {
        this.mapper = mapper;
    }

    /** lambda 链式条件 */
    private LambdaQueryChainWrapper<RbacPerm> query() {
        return ChainWrappers.lambdaQueryChain(mapper, RbacPerm.class);
    }

    /** 新增权限 */
    public void save(RbacPerm row) {
        Assert.nonNull(row, "table#RbacPerm不能为空");
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

    /** 按主键更新权限 */
    public void update(RbacPerm row) {
        Assert.nonNull(row, "table#RbacPerm不能为空");
        Assert.nonNull(row.getId(), "table#RbacPerm ID不能为空");
        row.setUpdateTime(Instant.now());
        int rows = mapper.updateById(row);
        Assert.isTrue(rows == 1, "table#RbacPerm更新失败，ID[{}]不存在", row.getId());
    }

    /** 未软删的权限 */
    public RbacPerm findActive(Long id) {
        if (id == null) {
            return null;
        }
        RbacPerm entity = mapper.selectById(id);
        return entity != null && entity.getDeleteTime() == null ? entity : null;
    }

    /** 按主键批量查询未删除的权限（一次查库，不随权限数增长） */
    public List<RbacPerm> listActiveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectByIds(ids).stream().filter(row -> row.getDeleteTime() == null).toList();
    }

    /** 按编码查询未删除的权限 */
    public RbacPerm findByCode(String code) {
        return query().eq(RbacPerm::getCode, code).isNull(RbacPerm::getDeleteTime).one();
    }

    /** API 权限 method+path 是否已被占用（可排除当前 ID） */
    public boolean existsApiKey(String method, String path, Long excludeId) {
        var q = query()
                .eq(RbacPerm::getHttpMethod, method)
                .eq(RbacPerm::getHttpPath, path)
                .isNull(RbacPerm::getDeleteTime);
        if (excludeId != null && excludeId > 0) {
            q.ne(RbacPerm::getId, excludeId);
        }
        return q.count() > 0;
    }

    /** 目录权限 uiPath 是否已被占用（可排除当前 ID） */
    public boolean existsMenuPath(String uiPath, Long excludeId) {
        var q = query().eq(RbacPerm::getUiPath, uiPath).isNull(RbacPerm::getDeleteTime);
        if (excludeId != null && excludeId > 0) {
            q.ne(RbacPerm::getId, excludeId);
        }
        return q.count() > 0;
    }

    /** 分页列表；page 最小 1，size 默认 20、上限 100 */
    public Page<RbacPerm> pageList(int page, int size, String keyword, String type, Integer status) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
        var q = query().isNull(RbacPerm::getDeleteTime);
        if (StringUtils.isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            q.and(w -> w.like(RbacPerm::getCode, like)
                    .or().like(RbacPerm::getName, like)
                    .or().like(RbacPerm::getHttpPath, like)
                    .or().like(RbacPerm::getUiPath, like));
        }
        if (StringUtils.isNotBlank(type)) {
            q.eq(RbacPerm::getType, type.trim());
        }
        if (status != null) {
            q.eq(RbacPerm::getStatus, status);
        }
        q.orderByAsc(RbacPerm::getId);
        return q.page(new Page<>(page, size));
    }

}
