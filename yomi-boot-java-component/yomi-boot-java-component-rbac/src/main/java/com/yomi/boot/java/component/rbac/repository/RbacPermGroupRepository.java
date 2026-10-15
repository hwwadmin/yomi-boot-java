package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermGroupMapper;
import org.apache.commons.lang3.StringUtils;

/**
 * sys_rbac_perm_group 数据访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
public class RbacPermGroupRepository extends MyBatisDao<RbacPermGroupMapper, RbacPermGroup>
        implements IRbacPermGroupRepository {

    public RbacPermGroupRepository(RbacPermGroupMapper mapper) {
        super(mapper);
    }

    /** 按 ID 查询未删除的权限组 */
    public RbacPermGroup findActive(Long id) {
        if (id == null) {
            return null;
        }
        RbacPermGroup entity = getMapper().selectById(id);
        return entity != null && entity.exist() ? entity : null;
    }

    /** 按编码查询未删除的权限组 */
    public RbacPermGroup findByCode(String code) {
        return lambdaQuery().eq(RbacPermGroup::getCode, code).isNull(RbacPermGroup::getDeleteTime).one();
    }

    /** 分页列表；page 最小 1，size 默认 20、上限 100 */
    public Page<RbacPermGroup> pageList(int page, int size, String keyword, Integer status) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
        var q = lambdaQuery().isNull(RbacPermGroup::getDeleteTime);
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
