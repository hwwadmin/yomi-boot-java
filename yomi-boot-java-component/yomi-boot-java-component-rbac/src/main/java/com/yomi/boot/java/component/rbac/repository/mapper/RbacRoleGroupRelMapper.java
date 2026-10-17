package com.yomi.boot.java.component.rbac.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.rbac.entity.RbacRoleGroupRel;
import org.apache.ibatis.annotations.Mapper;


/**
 * sys_rbac_role_group_rel Mapper：由 {@link RbacRelationRepository}、{@link RbacSnapshotRepository} 使用；其余代码经仓储端口访问
 *
 * @author zwei
 */
@Mapper
public interface RbacRoleGroupRelMapper extends BaseMapper<RbacRoleGroupRel> {
}
