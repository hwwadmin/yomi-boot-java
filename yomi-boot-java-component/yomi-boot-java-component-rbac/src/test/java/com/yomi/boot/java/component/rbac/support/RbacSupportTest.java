package com.yomi.boot.java.component.rbac.support;

import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.enums.RbacBuiltinRole;
import com.yomi.boot.java.component.rbac.enums.RbacStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RbacSupportTest {

    @Test
    void builtinSuperAdminDetected() {
        RbacRole role = new RbacRole();
        role.setCode(RbacBuiltinRole.SUPER_ADMIN);
        assertTrue(RbacSupport.isBuiltinRole(role));
    }

    @Test
    void builtinFlagDetected() {
        RbacRole role = new RbacRole();
        role.setCode("custom");
        role.setBuiltin(1);
        assertTrue(RbacSupport.isBuiltinRole(role));
    }

    @Test
    void normalRoleNotBuiltin() {
        RbacRole role = new RbacRole();
        role.setCode("ops");
        role.setBuiltin(0);
        role.setStatus(RbacStatus.ACTIVE.getCode());
        assertFalse(RbacSupport.isBuiltinRole(role));
    }

}
