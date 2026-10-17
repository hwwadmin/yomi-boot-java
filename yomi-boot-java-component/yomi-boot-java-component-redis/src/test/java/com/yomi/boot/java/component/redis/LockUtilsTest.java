package com.yomi.boot.java.component.redis;

import com.yomi.boot.java.base.exception.UtilsException;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LockUtils#execute} 的异常语义（两段分开）：
 * <ul>
 *     <li>锁内<b>业务异常原样抛出</b>，不包装成锁文案，排查时能直接看到原始报错；</li>
 *     <li>锁忙等业务异常原样抛出；<b>只有抢锁阶段的基础设施异常</b>按 exceptionMsg 包装；</li>
 *     <li>业务失败也释放锁；锁未到手时保持未解锁。</li>
 * </ul>
 */
class LockUtilsTest {

    private static final String LOCK_MSG = "演示抢锁失败";

    @Test
    void businessExceptionPropagatesUntouchedAndReleasesLock() {
        UnlockRecorder lock = new UnlockRecorder();
        IllegalStateException businessFailure = new IllegalStateException("下游数据库故障");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LockUtils.execute(lock.proxy(), Lock::lock, () -> {
                    throw businessFailure;
                }, LOCK_MSG));

        assertSame(businessFailure, thrown, "业务异常必须原样抛出（同一实例），不得包装成 UtilsException");
        assertFalse(String.valueOf(thrown.getMessage()).contains(LOCK_MSG));
        assertEquals(1, lock.unlocks.get(), "业务失败也必须释放锁");
    }

    @Test
    void lockBusyPropagatesAndDoesNotUnlock() {
        UnlockRecorder lock = new UnlockRecorder();
        LockBusyException busy = new LockBusyException("demo:key");

        LockBusyException thrown = assertThrows(LockBusyException.class,
                () -> LockUtils.execute(lock.proxy(), l -> {
                    throw busy;
                }, () -> 1, LOCK_MSG));

        assertSame(busy, thrown, "锁忙异常原样抛（宿主据此判断抢锁失败）");
        assertEquals(0, lock.unlocks.get(), "锁未到手不得误解锁");
    }

    @Test
    void acquisitionInfraFailureIsWrappedWithLockMessage() {
        UnlockRecorder lock = new UnlockRecorder();
        RuntimeException infra = new RuntimeException("redis connection refused");

        UtilsException thrown = assertThrows(UtilsException.class,
                () -> LockUtils.execute(lock.proxy(), l -> {
                    throw infra;
                }, () -> 1, LOCK_MSG));

        assertTrue(String.valueOf(thrown.getMessage()).contains(LOCK_MSG),
                "抢锁阶段的基础设施异常仍按 exceptionMsg 包装: " + thrown.getMessage());
        assertSame(infra, thrown.getCause(), "原始异常必须保留在 cause 链");
        assertEquals(0, lock.unlocks.get());
    }

    @Test
    void businessSuccessReturnsValueAndReleasesLock() {
        UnlockRecorder lock = new UnlockRecorder();

        String result = LockUtils.execute(lock.proxy(), Lock::lock, () -> "ok", LOCK_MSG);

        assertEquals("ok", result);
        assertEquals(1, lock.unlocks.get());
    }

    @Test
    void unlockFailureDoesNotMaskBusinessException() {
        UnlockRecorder lock = new UnlockRecorder();
        lock.failUnlock = true;
        IllegalStateException businessFailure = new IllegalStateException("业务失败");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LockUtils.execute(lock.proxy(), Lock::lock, () -> {
                    throw businessFailure;
                }, LOCK_MSG));

        assertSame(businessFailure, thrown, "释放失败不得遮蔽业务异常");
    }

    /** Lock 桩：记录 unlock 次数，可选让 unlock 抛错 */
    static class UnlockRecorder {

        final AtomicInteger unlocks = new AtomicInteger();
        boolean failUnlock;

        Lock proxy() {
            return (Lock) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Lock.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "unlock" -> {
                            unlocks.incrementAndGet();
                            if (failUnlock) {
                                throw new IllegalStateException("锁已过期易主");
                            }
                            yield null;
                        }
                        default -> null;
                    });
        }
    }

}
