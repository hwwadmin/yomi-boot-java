package com.yomi.boot.java.component.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 表 sys_rbac_role_group_rel
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_rbac_role_group_rel")
public class RbacRoleGroupRel extends BaseEntity {

    /** 角色 ID */
    private Long roleId;

    /** 权限组 ID */
    private Long groupId;

}
