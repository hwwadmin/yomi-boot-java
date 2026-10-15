package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacGroupPermRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleGroupRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacUserRoleRelMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仓储参数健壮性：null/空集合不应 NPE（公开方法被直接调用是现实场景）
 */
class RbacRelationRepositoryTest {

    @Test
    void listRolesByUserIdsToleratesNull() {
        RecordingMappers mappers = new RecordingMappers();
        RbacRelationRepository repository = mappers.repository();

        Map<Long, List<RbacRole>> result = assertDoesNotThrow(() -> repository.listRolesByUserIds(null));

        assertTrue(result.isEmpty(), "null 入参应返回空 Map 而不是 NPE");
        assertEquals(0, mappers.selectCalls, "null/空入参不应查库");
    }

    @Test
    void listRolesByUserIdsToleratesEmpty() {
        RecordingMappers mappers = new RecordingMappers();

        assertTrue(mappers.repository().listRolesByUserIds(List.of()).isEmpty());
        assertEquals(0, mappers.selectCalls);
    }

    @Test
    void replaceUserRolesToleratesNullRoleIds() {
        RecordingMappers mappers = new RecordingMappers();

        assertDoesNotThrow(() -> mappers.repository().replaceUserRoles(7L, null, 1L));

        assertEquals(1, mappers.deletes, "null 列表=清空绑定：仍应执行删除");
        assertEquals(0, mappers.inserts, "null 列表不应插入任何行");
    }

    @Test
    void replaceRoleGroupsAndPermsTolerateNull() {
        RecordingMappers mappers = new RecordingMappers();
        RbacRelationRepository repository = mappers.repository();

        assertDoesNotThrow(() -> repository.replaceRoleGroups(1L, null, 1L));
        assertDoesNotThrow(() -> repository.replaceGroupPerms(1L, null, 1L));
        assertEquals(2, mappers.deletes);
        assertEquals(0, mappers.inserts);
    }

    /** 代理 Mapper：只记录调用次数，不触达数据库 */
    static class RecordingMappers {

        int selectCalls;
        int deletes;
        int inserts;

        RbacRelationRepository repository() {
            RecordingMappers self = this;
            RbacUserRoleRelMapper userRoleMapper = proxy(RbacUserRoleRelMapper.class, self);
            RbacRoleGroupRelMapper roleGroupMapper = proxy(RbacRoleGroupRelMapper.class, self);
            RbacGroupPermRelMapper groupPermMapper = proxy(RbacGroupPermRelMapper.class, self);
            RbacRoleRepository roleRepository = new RbacRoleRepository(null);
            return new RbacRelationRepository(userRoleMapper, roleGroupMapper, groupPermMapper, roleRepository);
        }

        /** 从 ApplicationContext 取 Bean 的 ObjectProvider */
        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, RecordingMappers recorder) {
            return (T) Proxy.newProxyInstance(RecordingMappers.class.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "delete" -> {
                            recorder.deletes++;
                            yield 1;
                        }
                        case "insert" -> {
                            recorder.inserts++;
                            yield 1;
                        }
                        case "selectList" -> {
                            recorder.selectCalls++;
                            yield new ArrayList<>();
                        }
                        default -> null;
                    });
        }
    }

}
