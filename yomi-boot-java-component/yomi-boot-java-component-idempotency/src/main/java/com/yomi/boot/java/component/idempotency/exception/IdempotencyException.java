package com.yomi.boot.java.component.idempotency.exception;

import com.yomi.boot.java.base.exception.BizException;

/**
 * 构造带统一 errorCode 的 BizException，供守卫抛出
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

    /**
     * 命名空间、幂等键或请求指纹超过库列上限
     * <p>提前拒绝，避免落到驱动层的 Data too long / value too long
     */
    public static BizException valueTooLong(String field, int length, int max) {
        return BizException.exception(IdempotencyErrorCode.KEY_INVALID,
                field + "超长：" + length + " 字符 > 库列上限 " + max + "，请缩短或改用摘要");
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

    /**
     * 线程上绑定的事务与本次记录库不一致：记录会写在另一条事务里（提交/回滚各自独立）
     *
     * @param recordLibrary  本次幂等记录所在库（注册名）
     * @param boundLibraries 线程上实际绑着事务的其它库
     */
    public static BizException foreignTransaction(String recordLibrary, String boundLibraries) {
        return BizException.exception(IdempotencyErrorCode.FOREIGN_TRANSACTION,
                "幂等要求记录与业务同事务：本次记录库是 " + recordLibrary + "，但线程上绑着别的库的事务（"
                        + boundLibraries + "）——记录会另起一条事务并先提交，外层回滚撤不回它。"
                        + "请让该用例只碰一个库（@Transactional 与记录库一致，不写 transactionManager），"
                        + "或把幂等闭包里的业务挪到记录库执行（跨库一致性用事件编排，见 component-event）");
    }

    /**
     * 线程上同时绑了多个库的事务，无法判定记录写哪个库
     *
     * @param boundLibraries 线程上绑着事务的库
     */
    public static BizException ambiguousTransaction(String boundLibraries) {
        return BizException.exception(IdempotencyErrorCode.FOREIGN_TRANSACTION,
                "线程上同时绑定了多个数据源的事务（" + boundLibraries + "），无法判定幂等记录写哪个库："
                        + "请显式传入 TransactionTemplate（IdempotencyUtils.execute/run 的 template 入参）指明记录库");
    }

    /** 没有可用于幂等记录的数据库事务：调用点不在数据源事务里，且没传事务模板 */
    public static BizException transactionRequired() {
        return BizException.exception(IdempotencyErrorCode.TRANSACTION_REQUIRED,
                "幂等需要数据库事务边界：请在调用点所在用例上加 @Transactional（不要写 transactionManager，"
                        + "按类型落主库），或把 TransactionTemplate 显式传给 IdempotencyUtils.execute/run"
                        + "——组件不自行创建事务，没有边界时记录会各自自动提交（业务失败后该键会卡在处理中直到清理）");
    }

    /** 调用点有事务，但它不是 {@code @Transactional} 托管的（拿不到事务状态） */
    public static BizException transactionStatusUnavailable() {
        return BizException.exception(IdempotencyErrorCode.TRANSACTION_REQUIRED,
                "调用点的事务无法被幂等接管（不是 @Transactional 托管的，拿不到事务状态，唯一键冲突后无法回滚到保存点）："
                        + "请把外层的 TransactionTemplate 显式传给 IdempotencyUtils.execute/run，"
                        + "或把外层事务改成 @Transactional");
    }

    /**
     * 传入的 TransactionTemplate 不能用作幂等边界
     *
     * @param detail 具体原因（传播行为 / 只读 / 事务管理器类型）
     */
    public static BizException transactionInvalid(String detail) {
        return BizException.exception(IdempotencyErrorCode.TRANSACTION_INVALID,
                "传入的事务模板不能用作幂等边界：" + detail
                        + "。请传一个传播为 REQUIRED、非只读、绑定 DataSourceTransactionManager 的模板");
    }

    /**
     * 本次事务所在的数据源没有注册幂等记录表
     *
     * @param dataSource 数据源展示名（类型名）
     */
    public static BizException datasourceUnregistered(String dataSource) {
        return BizException.exception(IdempotencyErrorCode.DATASOURCE_UNREGISTERED,
                "本次事务所在的数据源（" + dataSource + "）未注册幂等记录表：请在装配期用 IdempotencyDataSources#register"
                        + " 注册它（注册即获得自动清理），或把幂等调用放回已注册的库（默认只有主库）");
    }

}
