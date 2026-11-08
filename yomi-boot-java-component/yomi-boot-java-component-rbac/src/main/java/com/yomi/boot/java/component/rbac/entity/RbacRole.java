package com.yomi.boot.java.component.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 表 t_sys_rbac_role
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("t_sys_rbac_role")
public class RbacRole {

    /** 主键 */
    @TableId
    private Long id;

    /** 角色编码，唯一 */
    private String code;

    /** 角色名称 */
    private String name;

    /** 1 内置 / 0 普通 */
    private Integer builtin;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private Instant createTime;

    /** 创建人ID */
    private Long createBy;

    /** 更新时间 */
    private Instant updateTime;

    /** 更新人ID */
    private Long updateBy;

    /** 删除时间 */
    private Instant deleteTime;

    /** 删除人ID */
    private Long deleteBy;

}
