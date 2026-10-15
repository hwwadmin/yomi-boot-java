package com.yomi.boot.java.component.rbac.convert;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.model.RbacPermGroupModel;
import com.yomi.boot.java.component.rbac.model.RbacPermModel;
import com.yomi.boot.java.component.rbac.model.RbacRoleModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RbacModelConverter} 字段映射完整性
 *
 * @author zwei
 */
class RbacModelConverterTest {

    private static final Instant CREATE_TIME = Instant.parse("2026-10-13T01:02:03Z");

    private static final Instant UPDATE_TIME = Instant.parse("2026-10-13T04:05:06Z");

    @Test
    void toPermModelCopiesAllFields() throws Exception {
        RbacPerm entity = new RbacPerm();
        entity.setId(11L);
        entity.setCreateTime(CREATE_TIME);
        entity.setCreateBy(12L);
        entity.setUpdateTime(UPDATE_TIME);
        entity.setUpdateBy(13L);
        entity.setCode("perm:demo");
        entity.setName("演示权限");
        entity.setType("API");
        entity.setUiPath("/demo");
        entity.setHttpMethod("GET");
        entity.setHttpPath("/api/demo");
        entity.setStatus(1);
        entity.setRemark("备注");

        RbacPermModel model = RbacModelConverter.toPermModel(entity);

        assertAllCopied(entity, model);
    }

    @Test
    void toPermGroupModelCopiesBaseFieldsAndLeavesPermIdsNull() throws Exception {
        RbacPermGroup entity = new RbacPermGroup();
        entity.setId(21L);
        entity.setCreateTime(CREATE_TIME);
        entity.setCreateBy(22L);
        entity.setUpdateTime(UPDATE_TIME);
        entity.setUpdateBy(23L);
        entity.setCode("group:demo");
        entity.setName("演示权限组");
        entity.setStatus(1);
        entity.setRemark("备注");

        RbacPermGroupModel model = RbacModelConverter.toPermGroupModel(entity);

        assertAllCopied(entity, model);
        assertNull(model.getPermIds(), "绑定的权限ID应由服务层详情查询补全");
    }

    @Test
    void toRoleModelCopiesBaseFieldsAndLeavesGroupIdsNull() throws Exception {
        RbacRole entity = new RbacRole();
        entity.setId(31L);
        entity.setCreateTime(CREATE_TIME);
        entity.setCreateBy(32L);
        entity.setUpdateTime(UPDATE_TIME);
        entity.setUpdateBy(33L);
        entity.setCode("role:demo");
        entity.setName("演示角色");
        entity.setBuiltin(0);
        entity.setStatus(1);
        entity.setRemark("备注");

        RbacRoleModel model = RbacModelConverter.toRoleModel(entity);

        assertAllCopied(entity, model);
        assertNull(model.getGroupIds(), "绑定的权限组ID应由服务层详情查询补全");
    }

    /** 逐字段比对模型与实体同名字段，实体新增字段漏映射时失败 */
    private static void assertAllCopied(Object entity, Object model) throws Exception {
        int copied = 0;
        for (Field field : model.getClass().getDeclaredFields()) {
            Field source = findField(entity.getClass(), field.getName());
            if (source == null) {
                continue;
            }
            field.setAccessible(true);
            source.setAccessible(true);
            assertEquals(source.get(entity), field.get(model), "字段未复制: " + field.getName());
            copied++;
        }
        assertTrue(copied > 0, "未比对到任何字段，反射匹配失效");
    }

    /** 沿类层级按名字查找字段（实体字段含 BaseEntity 继承部分） */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 当前类无该字段，继续向父类查找
            }
        }
        return null;
    }

}
