package com.yomi.boot.java.component.rbac.input;

import lombok.Data;


/**
 * 创建权限组入参
 *
 * @author zwei
 */
@Data
public class CreatePermGroupInput {

    /** 权限组编码 */
    private String code;

    /** 权限组名称 */
    private String name;

    /** 备注 */
    private String remark;

    /** 操作人用户 ID */
    private long actorId;

}
