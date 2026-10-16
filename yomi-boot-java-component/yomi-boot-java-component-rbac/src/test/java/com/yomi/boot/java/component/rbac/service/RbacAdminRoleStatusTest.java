package com.yomi.boot.java.component.rbac.service;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.rbac.repository.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 内置角色状态：须保持启用；重复启用请求幂等，误禁用时可经启用 API 恢复，禁用请求应被拒绝。
 */
class RbacAdminRoleStatusTest {

    @Test
    void enablingAlreadyActiveBuiltinRoleIsIdempotent() {
        StubRoleRepository roleRepository = new StubRoleRepository(RbacStatus.ACTIVE.getCode());
        TestService service = service(roleRepository);

        service.setRoleStatus(1L, 9L, RbacStatus.ACTIVE.getCode());

        assertEquals(0, roleRepository.updates.size(), "已启用时不应写库");
    }

    @Test
    void enablingDisabledBuiltinRoleRestoresIt() {
        StubRoleRepository roleRepository = new StubRoleRepository(RbacStatus.DISABLED.getCode());
        TestService service = service(roleRepository);

        service.setRoleStatus(1L, 9L, RbacStatus.ACTIVE.getCode());

        assertEquals(1, roleRepository.updates.size(), "被误禁用的内置角色必须恢复为启用");
        assertEquals(RbacStatus.ACTIVE.getCode(), roleRepository.updates.get(0).getStatus());
    }

    @Test
    void disablingBuiltinRoleIsRejected() {
        StubRoleRepository roleRepository = new StubRoleRepository(RbacStatus.ACTIVE.getCode());
        TestService service = service(roleRepository);

        BizException ex = assertThrows(BizException.class,
                () -> service.setRoleStatus(1L, 9L, RbacStatus.DISABLED.getCode()));

        assertEquals(RbacErrorCode.BUILTIN_IMMUTABLE.getCode(), ex.getErrorCode());
        assertEquals(0, roleRepository.updates.size());
    }

    /** 构造被测 Service 实例 */
    private static TestService service(StubRoleRepository roleRepository) {
        return new TestService(new StubPermRepository(), new StubPermGroupRepository(), roleRepository,
                new StubRelationRepository(roleRepository), null);
    }

    static class TestService extends RbacAdminService {

        TestService(RbacPermRepository permRepository, RbacPermGroupRepository permGroupRepository,
                    RbacRoleRepository roleRepository, RbacRelationRepository relationRepository,
                    RbacAuthRepository authRepository) {
            super(permRepository, permGroupRepository, roleRepository, relationRepository, authRepository);
        }
    }

    static class StubRoleRepository extends RbacRoleRepository {

        final List<RbacRole> updates = new ArrayList<>();
        private final int currentStatus;

        StubRoleRepository(int currentStatus) {
            super(null);
            this.currentStatus = currentStatus;
        }

        @Override
        public RbacRole findActive(Long id) {
            RbacRole role = new RbacRole();
            role.setId(id);
            role.setCode(RbacBuiltinRole.SUPER_ADMIN);
            role.setBuiltin(1);
            role.setStatus(currentStatus);
            return role;
        }

        @Override
        public void update(RbacRole entity) {
            updates.add(entity);
        }
    }

    static class StubRelationRepository extends RbacRelationRepository {

        StubRelationRepository(RbacRoleRepository roleRepository) {
            super(null, null, null, roleRepository);
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
