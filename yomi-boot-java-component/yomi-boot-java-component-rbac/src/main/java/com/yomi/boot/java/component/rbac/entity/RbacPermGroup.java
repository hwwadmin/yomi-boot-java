package com.yomi.boot.java.component.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 表 sys_rbac_perm_group
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_rbac_perm_group")
public class RbacPermGroup extends BaseEntity {

    /** 权限组编码，唯一 */
    private String code;

    /** 权限组名称 */
    private String name;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

}
