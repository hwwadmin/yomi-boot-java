package com.yomi.boot.java.component.rbac.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * 角色视图（管理服务出入参模型，与表实体相互隔离）
 * <p>列表查询只填基础字段；详情查询额外填充 {@link #groupIds}
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
public class RbacRoleModel {

    /** 主键ID */
    private Long id;

    /** 角色编码 */
    private String code;

    /** 角色名称 */
    private String name;

    /** 是否内置角色（1=内置） */
    private Integer builtin;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private Instant createTime;

    /** 更新时间 */
    private Instant updateTime;

    /** 绑定的权限组ID列表（详情查询返回；列表查询为 null） */
    private List<Long> groupIds;

}
