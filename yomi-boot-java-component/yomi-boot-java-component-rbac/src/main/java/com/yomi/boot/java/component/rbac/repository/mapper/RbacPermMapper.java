package com.yomi.boot.java.component.rbac.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import org.apache.ibatis.annotations.Mapper;

/**
 * t_sys_rbac_perm：由 {@link RbacPermRepository}、{@link RbacSnapshotRepository} 使用；其余代码经仓储端口访问
 *
 * @author zwei
 */
@Mapper
public interface RbacPermMapper extends BaseMapper<RbacPerm> {
}
