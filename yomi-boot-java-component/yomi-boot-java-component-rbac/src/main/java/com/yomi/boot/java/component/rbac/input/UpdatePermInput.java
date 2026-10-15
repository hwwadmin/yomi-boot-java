package com.yomi.boot.java.component.rbac.input;

import lombok.Data;


/**
 * 更新权限入参（不可改 code、type）
 *
 * @author zwei
 */
@Data
public class UpdatePermInput {

    /** 权限 ID */
    private long id;

    /** 权限名称 */
    private String name;

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
