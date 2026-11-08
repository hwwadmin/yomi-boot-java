package com.yomi.boot.java.component.rbac.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import org.apache.ibatis.annotations.Mapper;


/**
 * t_sys_rbac_perm_group Mapper：由 {@link RbacPermGroupRepository}、{@link RbacSnapshotRepository} 使用；其余代码经仓储端口访问
 *
 * @author zwei
 */
@Mapper
public interface RbacPermGroupMapper extends BaseMapper<RbacPermGroup> {
}
