package com.yomi.boot.java.component.rbac.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.entity.RbacUserRoleRel;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacGroupPermRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleGroupRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacUserRoleRelMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仓储参数健壮性（null/空集合不抛 NPE）与批量查角色的一次性（查询次数与角色数/关系行数无关）
 */
class RbacRelationRepositoryTest {

    /** lambda 条件需要 MP 表信息缓存，纯单测环境手工注册 */
    @BeforeAll
    static void registerEntities() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RbacUserRoleRel.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RbacRole.class);
    }

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

    /**
     * 角色必须一次批量取回：一趟 selectByIds 覆盖全部角色，
     * 查询次数与角色数、引用已软删角色的关系行数均无关
     */
    @Test
    void listRolesByUserIdsQueriesRolesOnceRegardlessOfRelationRows() {
        RecordingMappers mappers = new RecordingMappers();
        // 两个用户共享两个角色，另有 3 行引用已软删的角色 13
        mappers.rels = List.of(rel(1L, 11L), rel(1L, 12L), rel(1L, 13L), rel(2L, 13L), rel(2L, 11L));
        mappers.roles = List.of(role(11L), role(12L), deletedRole(13L));

        Map<Long, List<RbacRole>> result = mappers.repository().listRolesByUserIds(List.of(1L, 2L));

        assertEquals(1, mappers.selectCalls, "关系表只查一次");
        assertEquals(1, mappers.roleBatchSelects, "角色批量查询只发一条，与角色数/关系行数无关");
        assertEquals(0, mappers.roleSingleSelects, "不得逐角色 selectById");
        assertEquals(List.of(11L, 12L), result.get(1L).stream().map(RbacRole::getId).toList(),
                "已软删角色不参与结果");
        assertEquals(List.of(11L), result.get(2L).stream().map(RbacRole::getId).toList());
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
        int roleBatchSelects;
        int roleSingleSelects;

        List<RbacUserRoleRel> rels = List.of();
        List<RbacRole> roles = List.of();

        RbacRelationRepository repository() {
            RecordingMappers self = this;
            RbacUserRoleRelMapper userRoleMapper = proxy(RbacUserRoleRelMapper.class, self);
            RbacRoleGroupRelMapper roleGroupMapper = proxy(RbacRoleGroupRelMapper.class, self);
            RbacGroupPermRelMapper groupPermMapper = proxy(RbacGroupPermRelMapper.class, self);
            RbacRoleRepository roleRepository = new RbacRoleRepository(roleMapper(self));
            return new RbacRelationRepository(userRoleMapper, roleGroupMapper, groupPermMapper, roleRepository);
        }

        /** 关系表 Mapper：selectList 返回预置关系行，insert/delete 只计数 */
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
                            yield new ArrayList<>(recorder.rels);
                        }
                        default -> null;
                    });
        }

        /** 角色 Mapper：记录批量与单条查询次数（批量走 selectByIds，单条走 selectById） */
        private static RbacRoleMapper roleMapper(RecordingMappers recorder) {
            return (RbacRoleMapper) Proxy.newProxyInstance(RecordingMappers.class.getClassLoader(),
                    new Class<?>[]{RbacRoleMapper.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "selectByIds" -> {
                            recorder.roleBatchSelects++;
                            yield new ArrayList<>(recorder.roles);
                        }
                        case "selectById" -> {
                            recorder.roleSingleSelects++;
                            Long id = (Long) args[0];
                            yield recorder.roles.stream().filter(role -> id.equals(role.getId())).findFirst()
                                    .orElse(null);
                        }
                        default -> null;
                    });
        }
    }

    private static RbacUserRoleRel rel(long userId, long roleId) {
        RbacUserRoleRel rel = new RbacUserRoleRel();
        rel.setUserId(userId);
        rel.setRoleId(roleId);
        return rel;
    }

    private static RbacRole role(long id) {
        RbacRole role = new RbacRole();
        role.setId(id);
        role.setCode("role-" + id);
        role.setStatus(1);
        return role;
    }

    private static RbacRole deletedRole(long id) {
        RbacRole role = role(id);
        role.setDeleteTime(Instant.now());
        return role;
    }

}
