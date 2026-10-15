package com.yomi.boot.java.component.rbac.input;

import lombok.Data;


/**
 * 创建权限入参
 *
 * @author zwei
 */
@Data
public class CreatePermInput {

    /** 权限编码 */
    private String code;

    /** 权限名称 */
    private String name;

    /** 类型：menu / api */
    private String type;

    /** 前端路由（menu） */
    private String uiPath;

    /** HTTP 方法（api） */
    private String httpMethod;

    /** HTTP 路径（api） */
    private String httpPath;

    /** 备注 */
    private String remark;

    /** 操作人用户 ID */
    private long actorId;

}
