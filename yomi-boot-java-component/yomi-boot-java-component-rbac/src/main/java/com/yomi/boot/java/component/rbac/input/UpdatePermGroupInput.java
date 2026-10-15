package com.yomi.boot.java.component.rbac.input;

import lombok.Data;


/**
 * 更新权限组入参
 *
 * @author zwei
 */
@Data
public class UpdatePermGroupInput {

    /** 权限组 ID */
    private long id;

    /** 权限组名称 */
    private String name;

    /** 备注 */
    private String remark;

    /** 操作人用户 ID */
    private long actorId;

}
