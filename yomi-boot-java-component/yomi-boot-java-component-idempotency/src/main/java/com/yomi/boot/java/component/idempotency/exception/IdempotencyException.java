package com.yomi.boot.java.component.idempotency.exception;

import com.yomi.boot.java.base.exception.BizException;

/**
 * 构造带统一 errorCode 的 BizException，供守卫与切面抛出
 *
 * @author zwei
 */
public final class IdempotencyException {

    private IdempotencyException() {
    }

    /** 命名空间或幂等键不合法 */
    public static BizException keyInvalid() {
        return BizException.exception(IdempotencyErrorCode.KEY_INVALID, "幂等键不合法");
    }

    /** 同键请求仍在处理中 */
    public static BizException processing() {
        return BizException.exception(IdempotencyErrorCode.PROCESSING, "同键请求仍在处理中");
    }

    /** 同键但请求指纹不一致 */
    public static BizException keyConflict() {
        return BizException.exception(IdempotencyErrorCode.KEY_CONFLICT, "幂等键冲突：请求指纹不一致");
    }

    /** 唯一键冲突却查不到幂等记录 */
    public static BizException duplicateWithoutRecord() {
        return BizException.exception(IdempotencyErrorCode.DUPLICATE_WITHOUT_RECORD, "唯一键冲突但查不到记录");
    }

    /**
     * 库中缓存的返回类型与当前方法声明不兼容
     *
     * @param storedType   库中记录的类型名
     * @param declaredType 当前声明的返回类型名
     */
    public static BizException resultTypeMismatch(String storedType, String declaredType) {
        return BizException.exception(IdempotencyErrorCode.RESULT_TYPE_MISMATCH,
                "幂等结果类型不匹配：库中为 " + storedType + "，当前声明为 " + declaredType
                        + "（返回类型已变更，请更换幂等键或清理旧记录）");
    }

}
