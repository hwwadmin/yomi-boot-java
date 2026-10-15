package com.yomi.boot.java.component.rbac.model;

import com.yomi.boot.java.base.model.PageRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 角色分页查询（分页参数继承 base 的 {@link PageRequest}）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class RbacRoleQuery extends PageRequest {

    /** 关键词（编码/名称） */
    private String keyword;

    /** 状态筛选 */
    private Integer status;

}
