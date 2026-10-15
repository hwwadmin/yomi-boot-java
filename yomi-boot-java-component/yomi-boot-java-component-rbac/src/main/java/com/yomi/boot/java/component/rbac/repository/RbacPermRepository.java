package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermMapper;
import org.apache.commons.lang3.StringUtils;

/**
 * sys_rbac_perm 数据访问；Bean 由 {@code RbacAutoConfiguration} 注册，不做组件扫描
 *
 * @author zwei
 */
public class RbacPermRepository extends MyBatisDao<RbacPermMapper, RbacPerm>
        implements IRbacPermRepository {

    public RbacPermRepository(RbacPermMapper mapper) {
        super(mapper);
    }

    /** 未软删的权限 */
    public RbacPerm findActive(Long id) {
        if (id == null) {
            return null;
        }
        RbacPerm entity = getMapper().selectById(id);
        return entity != null && entity.exist() ? entity : null;
    }

    /** 按编码查询未删除的权限 */
    public RbacPerm findByCode(String code) {
        return lambdaQuery().eq(RbacPerm::getCode, code).isNull(RbacPerm::getDeleteTime).one();
    }

    /** API 权限 method+path 是否已被占用（可排除当前 ID） */
    public boolean existsApiKey(String method, String path, Long excludeId) {
        var q = lambdaQuery()
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
        var q = lambdaQuery().eq(RbacPerm::getUiPath, uiPath).isNull(RbacPerm::getDeleteTime);
        if (excludeId != null && excludeId > 0) {
            q.ne(RbacPerm::getId, excludeId);
        }
        return q.count() > 0;
    }

    /** 分页列表；page 最小 1，size 默认 20、上限 100 */
    public Page<RbacPerm> pageList(int page, int size, String keyword, String type, Integer status) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
        var q = lambdaQuery().isNull(RbacPerm::getDeleteTime);
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
