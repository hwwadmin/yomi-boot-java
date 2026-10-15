package com.yomi.boot.java.component.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 表 sys_rbac_user_role_rel
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_rbac_user_role_rel")
public class RbacUserRoleRel extends BaseEntity {

    /** 用户 ID */
    private Long userId;

    /** 角色 ID */
    private Long roleId;

}
