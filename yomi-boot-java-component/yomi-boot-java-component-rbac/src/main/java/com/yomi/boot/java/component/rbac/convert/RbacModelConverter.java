package com.yomi.boot.java.component.rbac.convert;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.model.RbacPermGroupModel;
import com.yomi.boot.java.component.rbac.model.RbacPermModel;
import com.yomi.boot.java.component.rbac.model.RbacRoleModel;

/**
 * RBAC 实体 → 视图模型转换（各服务共用的基础字段映射；模型类只保留数据）
 *
 * @author zwei
 */
public final class RbacModelConverter {

    private RbacModelConverter() {
    }

    /** 权限实体 → 权限视图 */
    public static RbacPermModel toPermModel(RbacPerm entity) {
        RbacPermModel model = new RbacPermModel();
        model.setId(entity.getId());
        model.setCode(entity.getCode());
        model.setName(entity.getName());
        model.setType(entity.getType());
        model.setUiPath(entity.getUiPath());
        model.setHttpMethod(entity.getHttpMethod());
        model.setHttpPath(entity.getHttpPath());
        model.setStatus(entity.getStatus());
        model.setRemark(entity.getRemark());
        model.setCreateTime(entity.getCreateTime());
        model.setUpdateTime(entity.getUpdateTime());
        return model;
    }

    /** 权限组实体 → 权限组视图（基础字段；绑定的权限ID由服务层详情查询补全） */
    public static RbacPermGroupModel toPermGroupModel(RbacPermGroup entity) {
        RbacPermGroupModel model = new RbacPermGroupModel();
        model.setId(entity.getId());
        model.setCode(entity.getCode());
        model.setName(entity.getName());
        model.setStatus(entity.getStatus());
        model.setRemark(entity.getRemark());
        model.setCreateTime(entity.getCreateTime());
        model.setUpdateTime(entity.getUpdateTime());
        return model;
    }

    /** 角色实体 → 角色视图（基础字段；绑定的权限组ID由服务层详情查询补全） */
    public static RbacRoleModel toRoleModel(RbacRole entity) {
        RbacRoleModel model = new RbacRoleModel();
        model.setId(entity.getId());
        model.setCode(entity.getCode());
        model.setName(entity.getName());
        model.setBuiltin(entity.getBuiltin());
        model.setStatus(entity.getStatus());
        model.setRemark(entity.getRemark());
        model.setCreateTime(entity.getCreateTime());
        model.setUpdateTime(entity.getUpdateTime());
        return model;
    }

}
