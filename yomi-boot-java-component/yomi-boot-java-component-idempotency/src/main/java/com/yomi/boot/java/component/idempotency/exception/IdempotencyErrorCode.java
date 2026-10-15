package com.yomi.boot.java.component.idempotency.exception;

/**
 * 幂等业务异常使用的 errorCode 常量（对应 BizException.getErrorCode()）
 *
 * @author zwei
 */
public final class IdempotencyErrorCode {

    /** 命名空间或幂等键为空 */
    public static final String KEY_INVALID = "IDEMPOTENCY_KEY_INVALID";
    /** 同键仍有请求在处理中 */
    public static final String PROCESSING = "IDEMPOTENCY_PROCESSING";
    /** 同键但请求指纹不一致 */
    public static final String KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
    /** 唯一键冲突却查不到行（异常态） */
    public static final String DUPLICATE_WITHOUT_RECORD = "IDEMPOTENCY_DUPLICATE_WITHOUT_RECORD";
    /** 库中记录的返回类型与当前声明不兼容（接口返回类型已变更） */
    public static final String RESULT_TYPE_MISMATCH = "IDEMPOTENCY_RESULT_TYPE_MISMATCH";

    private IdempotencyErrorCode() {
    }

}
