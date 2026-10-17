package com.yomi.boot.java.base.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 响应码语义：码段划分与具体值是框架对外协议，逐个断言固定值
 */
class StatusCodeTest {

    @Test
    void successCode() {
        assertEquals(200, StatusCode.succeed.getCode());
    }

    @Test
    void httpAlignedCodes() {
        assertEquals(-1, StatusCode.unknown.getCode());
        assertEquals(400, StatusCode.commonError.getCode());
        assertEquals(401, StatusCode.invalidToken.getCode());
        assertEquals(403, StatusCode.unauthorized.getCode());
        assertEquals(404, StatusCode.notFound.getCode());
        assertEquals(405, StatusCode.methodNotAllowed.getCode());
        assertEquals(415, StatusCode.unsupportedMediaType.getCode());
        // 参数校验用 422（Unprocessable Content），未定义 412（Precondition Failed）
        assertEquals(422, StatusCode.parameterError.getCode());
        assertEquals(500, StatusCode.serverError.getCode());
        assertEquals(501, StatusCode.notImplemented.getCode());
    }

    @Test
    void internalCodesDecoupledFromHttp() {
        // 业务异常走 6xx 码段，与 HTTP 500 解耦
        assertEquals(601, StatusCode.bizError.getCode());
        assertEquals(604, StatusCode.utilsError.getCode());
        assertEquals(608, StatusCode.databaseError.getCode());
        assertEquals(610, StatusCode.assertError.getCode());
    }

    @Test
    void getEnumKnownCode() {
        assertSame(StatusCode.bizError, StatusCode.getEnum(601));
        assertSame(StatusCode.parameterError, StatusCode.getEnum(422));
        assertSame(StatusCode.succeed, StatusCode.getEnum(200));
    }

    @Test
    void getEnumUnknownFallsBackToCommonError() {
        assertSame(StatusCode.commonError, StatusCode.getEnum(9999));
        // 未知码映射为 commonError
        assertSame(StatusCode.commonError, StatusCode.getEnum(412));
        assertSame(StatusCode.commonError, StatusCode.getEnum(-100));
    }

}
