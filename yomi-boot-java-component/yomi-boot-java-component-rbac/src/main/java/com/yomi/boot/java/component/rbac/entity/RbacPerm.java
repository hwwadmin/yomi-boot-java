package com.yomi.boot.java.component.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 表 sys_rbac_perm
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_rbac_perm")
public class RbacPerm extends BaseEntity {

    /** 权限编码，唯一 */
    private String code;

    /** 权限名称 */
    private String name;

    /** menu / api */
    private String type;

    /** 前端路由（menu） */
    private String uiPath;

    /** HTTP 方法（api） */
    private String httpMethod;

    /** HTTP 路径（api） */
    private String httpPath;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

}
