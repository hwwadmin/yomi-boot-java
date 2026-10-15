package com.yomi.boot.java.component.rbac.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 错误码分层：403 = 确实没有权限；500 = 校验过程本身失败（基础设施异常）——两者混淆会让前端把故障当越权
 */
class RbacErrorCodeTest {

    @Test
    void forbiddenIsUnauthorized403() {
        assertEquals(StatusCode.unauthorized.getCode(), RbacErrorCode.FORBIDDEN.getResponseCode());
    }

    @Test
    void evalFailedIsServerError500() {
        assertEquals(StatusCode.serverError.getCode(), RbacErrorCode.EVAL_FAILED.getResponseCode(),
                "校验失败必须是 500（系统故障），不能与'无权限'同为 403");
    }

}
