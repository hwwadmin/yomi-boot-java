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
    /** 线程上绑定的事务与本次记录库不是同一个（或同时绑了多个，无法判定） */
    public static final String FOREIGN_TRANSACTION = "IDEMPOTENCY_FOREIGN_TRANSACTION";
    /** 没有可用于幂等记录的数据库事务：调用点不在事务里，也没传 TransactionTemplate */
    public static final String TRANSACTION_REQUIRED = "IDEMPOTENCY_TRANSACTION_REQUIRED";
    /** 传入的 TransactionTemplate 不合法（传播不是 REQUIRED / 只读 / 管理器不是 DataSourceTransactionManager） */
    public static final String TRANSACTION_INVALID = "IDEMPOTENCY_TRANSACTION_INVALID";
    /** 本次事务所在的数据源未注册幂等记录表 */
    public static final String DATASOURCE_UNREGISTERED = "IDEMPOTENCY_DATASOURCE_UNREGISTERED";

    private IdempotencyErrorCode() {
    }

}
