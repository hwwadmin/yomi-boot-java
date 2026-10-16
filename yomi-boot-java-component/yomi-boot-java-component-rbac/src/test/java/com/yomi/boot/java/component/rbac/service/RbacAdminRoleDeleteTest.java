package com.yomi.boot.java.component.rbac.service;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.rbac.input.CreateRoleInput;
import com.yomi.boot.java.component.rbac.repository.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 角色删除统一用物理删除：连带解除绑定、编码可复用；
 * 唯一键冲突（并发或历史软删遗留行占用）必须报 COD 冲突而非"服务器繁忙"兜底
 */
class RbacAdminRoleDeleteTest {

    @BeforeAll
    static void configureSnowflakeWorkerId() {
        // BaseEntity.init() 生成雪花主键，需要机器号；单元测试环境没有 yml/环境变量
        IdUtils.initialize(1L, 0L);
    }

    @Test
    void deleteRolePhysicallyDeletesAndClearsRelations() {
        StubRoleRepository roleRepository = new StubRoleRepository();
        StubRelationRepository relationRepository = new StubRelationRepository(roleRepository);
        RbacAdminRoleDeleteTest.TestService service = service(roleRepository, relationRepository);

        service.deleteRole(7L, 99L);

        assertEquals(List.of(7L), relationRepository.deletedRoleRelations, "删除角色必须连带清理绑定关系");
        assertEquals(List.of(7L), roleRepository.physicalDeletes, "删除必须是物理删除（编码可复用）");
        assertTrue(roleRepository.softDeletes.isEmpty(), "不应再走软删路径");
    }

    @Test
    void createRoleMapsDuplicateKeyToCodeConflict() {
        StubRoleRepository roleRepository = new StubRoleRepository();
        roleRepository.failNextSave = new DuplicateKeyException("uk_code");
        RbacAdminRoleDeleteTest.TestService service = service(roleRepository, new StubRelationRepository(roleRepository));

        CreateRoleInput input = new CreateRoleInput();
        input.setCode("ops");
        input.setName("运维");
        input.setActorId(1L);

        BizException ex = assertThrows(BizException.class, () -> service.createRole(input));

        assertEquals(RbacErrorCode.CODE_CONFLICT.getCode(), ex.getErrorCode(),
                "唯一键冲突必须映射为明确的编码冲突提示，而不是落到全局'服务器繁忙'兜底");
    }

    /** 构造被测 Service 实例 */
    private static TestService service(StubRoleRepository roleRepository, StubRelationRepository relationRepository) {
        return new TestService(new StubPermRepository(), new StubPermGroupRepository(), roleRepository,
                relationRepository, null);
    }

    static class TestService extends RbacAdminService {

        TestService(RbacPermRepository permRepository, RbacPermGroupRepository permGroupRepository,
                    RbacRoleRepository roleRepository, RbacRelationRepository relationRepository,
                    RbacAuthRepository authRepository) {
            super(permRepository, permGroupRepository, roleRepository, relationRepository, authRepository);
        }
    }

    static class StubRoleRepository extends RbacRoleRepository {

        final List<Long> physicalDeletes = new ArrayList<>();
        final List<Long> softDeletes = new ArrayList<>();
        RuntimeException failNextSave;
        private final AtomicLong ids = new AtomicLong(100L);

        StubRoleRepository() {
            super(null);
        }

        @Override
        public RbacRole findActive(Long id) {
            RbacRole role = new RbacRole();
            role.setId(id);
            role.setCode("ops");
            role.setBuiltin(0);
            return role;
        }

        @Override
        public RbacRole findByCode(String code) {
            return null;
        }

        @Override
        public void del(Long id) {
            physicalDeletes.add(id);
        }

        @Override
        public void softDel(RbacRole entity) {
            softDeletes.add(entity.getId());
        }

        @Override
        public void save(RbacRole entity) {
            if (failNextSave != null) {
                RuntimeException failure = failNextSave;
                failNextSave = null;
                throw failure;
            }
            entity.setId(ids.incrementAndGet());
        }
    }

    static class StubRelationRepository extends RbacRelationRepository {

        final List<Long> deletedRoleRelations = new ArrayList<>();

        StubRelationRepository(RbacRoleRepository roleRepository) {
            super(null, null, null, roleRepository);
        }

        @Override
        public void deleteRoleRelations(long roleId) {
            deletedRoleRelations.add(roleId);
        }
    }

    static class StubPermRepository extends RbacPermRepository {

        StubPermRepository() {
            super(null);
        }
    }

    static class StubPermGroupRepository extends RbacPermGroupRepository {

        StubPermGroupRepository() {
            super(null);
        }
    }

}
