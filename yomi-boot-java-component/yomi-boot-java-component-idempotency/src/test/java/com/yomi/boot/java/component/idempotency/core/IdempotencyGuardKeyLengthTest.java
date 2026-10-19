package com.yomi.boot.java.component.idempotency.core;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.exception.IdempotencyErrorCode;
import com.yomi.boot.java.component.idempotency.repository.IdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.store.IdempotencyCacheLoad;
import com.yomi.boot.java.component.idempotency.store.IdempotencyKeys;
import com.yomi.boot.java.component.idempotency.store.IdempotencyRedisStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键的长度契约（与 sys_idempotency_record 列宽一致）：
 * <ol>
 *   <li>身份字段 namespace / idempotencyKey 原样入库，超限必须在碰缓存、锁、库之前点名字段与上限
 *       （否则会落到驱动层 Data too long / value too long，报错不点名且同形状请求此后必然失败）；上限值本身合法；</li>
 *   <li>请求指纹不限长：构造时归一为 SHA-256 摘要，落库列宽与结果缓存键长度都由此封顶。</li>
 * </ol>
 */
class IdempotencyGuardKeyLengthTest {

    /** 是否读到了结果缓存：用来证明"长度校验先于缓存/锁/库" */
    private final AtomicBoolean storeTouched = new AtomicBoolean();

    @Test
    void rejectsOverlongNamespaceAndKey() {
        assertRejected(new IdempotencyKey("ns", "k".repeat(IdempotencyKey.MAX_KEY_LENGTH + 1), null),
                "幂等键", IdempotencyKey.MAX_KEY_LENGTH + 1, IdempotencyKey.MAX_KEY_LENGTH);
        assertRejected(new IdempotencyKey("n".repeat(IdempotencyKey.MAX_NAMESPACE_LENGTH + 1), "k", null),
                "幂等命名空间", IdempotencyKey.MAX_NAMESPACE_LENGTH + 1, IdempotencyKey.MAX_NAMESPACE_LENGTH);
    }

    /** 边界：正好等于上限不拦（发现即证明判据是"大于"而不是"大于等于"），直接走正常流程 */
    @Test
    void acceptsValuesExactlyAtLimit() {
        IdempotencyKey key = new IdempotencyKey(
                "n".repeat(IdempotencyKey.MAX_NAMESPACE_LENGTH),
                "k".repeat(IdempotencyKey.MAX_KEY_LENGTH),
                null);

        Object result = guard(IdempotencyCacheLoad.hit("cached")).executeInternal(key, String.class, () -> "should-not-run");

        assertEquals("cached", result, "上限值必须合法：校验通过后照常读缓存");
        assertTrue(storeTouched.get(), "上限值应放行到缓存读取");
    }

    /** 指纹不限长：超长指纹归一为摘要后照常放行，落库列宽与缓存键都被摘要封顶 */
    @Test
    void acceptsOverlongFingerprintByDigesting() {
        String raw = "body={" + "x".repeat(500) + "}";
        IdempotencyKey key = new IdempotencyKey("ns", "k", raw);

        assertEquals(IdempotencyKey.REQUEST_HASH_LENGTH, key.requestHash().length(),
                "指纹必须归一为固定长度的摘要");
        Object result = guard(IdempotencyCacheLoad.hit("cached")).executeInternal(key, String.class, () -> "x");
        assertEquals("cached", result);
        assertTrue(storeTouched.get(), "超长指纹不得被长度校验拦下");
    }

    /** 摘要口径：同原文（含首尾空白差异）同摘要、异原文异摘要；未传指纹时为 null */
    @Test
    void fingerprintIsCanonicalizedForStorageAndCacheKey() {
        IdempotencyKey first = new IdempotencyKey("ns", "k", "fp-A");
        IdempotencyKey sameRawWithSpaces = new IdempotencyKey("ns", "k", "  fp-A  ");
        IdempotencyKey other = new IdempotencyKey("ns", "k", "fp-B");

        assertEquals(first.requestHash(), sameRawWithSpaces.requestHash(), "同原文必须同摘要（首尾空白不参与）");
        assertNotEquals(first.requestHash(), other.requestHash(), "异原文必须异摘要");
        assertNull(new IdempotencyKey("ns", "k", "   ").requestHash(), "空白指纹视为未传");

        IdempotencyKeys keys = new IdempotencyKeys("yomi:test:idem");
        assertEquals(keys.resultKey(first), keys.resultKey(sameRawWithSpaces), "同指纹必须落在同一缓存键");
        assertNotEquals(keys.resultKey(first), keys.resultKey(other), "异指纹必须落在不同缓存键");
        assertFalse(keys.resultKey(first).contains("fp-A"), "缓存键只带摘要，不带原文");
        assertEquals(keys.resultKey(new IdempotencyKey("ns", "k", null)), keys.resultKey(new IdempotencyKey("ns", "k", "")),
                "未传指纹的缓存键不带指纹段");
    }

    /** 断言被点名拒绝：字段名、实际长度、上限都在消息里，且没有触达缓存 */
    private void assertRejected(IdempotencyKey key, String field, int length, int max) {
        storeTouched.set(false);

        BizException ex = assertThrows(BizException.class,
                () -> guard(IdempotencyCacheLoad.miss()).executeInternal(key, String.class, () -> "should-not-run"));

        assertEquals(IdempotencyErrorCode.KEY_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(field), ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(length)) && ex.getMessage().contains(String.valueOf(max)),
                ex.getMessage());
        assertFalse(storeTouched.get(), "长度校验必须先于缓存/锁/库");
    }

    /** 只装配校验路径需要的桩：命中的缓存桩用于放行，未命中也不会走到锁（长度校验通过后才会读缓存） */
    private IdempotencyGuard guard(IdempotencyCacheLoad loadResult) {
        IdempotencyRedisStore store = new IdempotencyRedisStore(null, null, null) {
            @Override
            public IdempotencyCacheLoad load(IdempotencyKey key, Type type) {
                storeTouched.set(true);
                return loadResult;
            }
        };
        return new IdempotencyGuard(new IdempotencyRecordRepository(null), store,
                new IdempotencyKeys("yomi:test:idem"), new TransactionTemplate(new NoTxManager()),
                new IdempotencyProperties());
    }

    /** 最小事务管理器：本类用例不真正开事务（校验失败即抛、其余用例命中缓存即返回） */
    static class NoTxManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

}
