package com.yomi.boot.java.component.rbac.input;

import lombok.Data;


/**
 * 创建角色入参
 *
 * @author zwei
 */
@Data
public class CreateRoleInput {

    /** 角色编码 */
    private String code;

    /** 角色名称 */
    private String name;

    /** 备注 */
    private String remark;

    /** 操作人用户 ID */
    private long actorId;

}
