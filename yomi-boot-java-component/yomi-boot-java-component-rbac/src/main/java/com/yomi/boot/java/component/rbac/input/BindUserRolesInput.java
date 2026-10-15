package com.yomi.boot.java.component.rbac.input;

import lombok.Data;

import java.util.List;


/**
 * 覆盖用户角色绑定入参
 *
 * @author zwei
 */
@Data
public class BindUserRolesInput {

    /** 目标用户 ID */
    private long userId;

    /** 覆盖后的角色 ID 列表 */
    private List<Long> roleIds;

    /** 操作人用户 ID */
    private long actorId;

}
