package com.yomi.boot.java.component.idempotency.core;

/**
 * sys_idempotency_record.status 取值
 *
 * @author zwei
 */
public final class IdempotencyStatus {

    /** 处理中 */
    public static final int PROCESSING = 1;
    /** 已完成（可重放结果） */
    public static final int COMPLETED = 2;

    private IdempotencyStatus() {
    }

}
