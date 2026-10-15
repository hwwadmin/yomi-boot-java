package com.yomi.boot.java.component.rbac.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


/**
 * sys_rbac_role Mapper：**仅供 {@link RbacRoleRepository}、{@link RbacSnapshotRepository} 使用**（其余代码一律经仓储端口访问，不直接调用）
 *
 * @author zwei
 */
@Mapper
public interface RbacRoleMapper extends BaseMapper<RbacRole> {

    /** 行锁查询内置超管角色（{@code lockSuperAdminRole} 运维用） */
    RbacRole lockSuperAdminRoleForUpdate(@Param("superAdminCode") String superAdminCode);

}
