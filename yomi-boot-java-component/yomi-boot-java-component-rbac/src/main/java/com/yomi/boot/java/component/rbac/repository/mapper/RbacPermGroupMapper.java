package com.yomi.boot.java.component.rbac.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import org.apache.ibatis.annotations.Mapper;


/**
 * sys_rbac_perm_group Mapper：**仅供 {@link RbacPermGroupRepository}、{@link RbacSnapshotRepository} 使用**（其余代码一律经仓储端口访问，不直接调用）
 *
 * @author zwei
 */
@Mapper
public interface RbacPermGroupMapper extends BaseMapper<RbacPermGroup> {
}
