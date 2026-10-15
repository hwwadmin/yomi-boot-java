package com.yomi.boot.java.component.redis.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.exception.AbstractRuntimeException;

/**
 * 未获取到分布式锁
 * 通过 {@link com.yomi.boot.java.component.redis.LockUtils#isLockBusy(Throwable)} 判断
 *
 * @author zwei
 */
public class LockBusyException extends AbstractRuntimeException {

    public LockBusyException(String lockKey) {
        super(StatusCode.utilsError.getCode(), "获取分布式锁失败: " + lockKey);
    }

}
