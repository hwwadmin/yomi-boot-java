package com.yomi.boot.java.component.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 表 sys_rbac_role
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_rbac_role")
public class RbacRole extends BaseEntity {

    /** 角色编码，唯一 */
    private String code;

    /** 角色名称 */
    private String name;

    /** 1 内置 / 0 普通 */
    private Integer builtin;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

}
