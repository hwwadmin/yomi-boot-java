package com.yomi.boot.java.component.rbac.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 权限视图（管理服务出入参用模型；**不暴露表实体**）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
public class RbacPermModel {

    /** 主键ID */
    private Long id;

    /** 权限编码 */
    private String code;

    /** 权限名称 */
    private String name;

    /** 权限类型（MENU/API） */
    private String type;

    /** 菜单 UI 路径 */
    private String uiPath;

    /** API 方法 */
    private String httpMethod;

    /** API 路径 */
    private String httpPath;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private Instant createTime;

    /** 更新时间 */
    private Instant updateTime;

}
