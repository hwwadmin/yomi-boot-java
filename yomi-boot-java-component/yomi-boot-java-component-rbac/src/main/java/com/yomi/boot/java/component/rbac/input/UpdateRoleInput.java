package com.yomi.boot.java.component.rbac.input;

import lombok.Data;


/**
 * 更新角色入参
 *
 * @author zwei
 */
@Data
public class UpdateRoleInput {

    /** 角色 ID */
    private long id;

    /** 角色名称 */
    private String name;

    /** 备注 */
    private String remark;

    /** 操作人用户 ID */
    private long actorId;

}
