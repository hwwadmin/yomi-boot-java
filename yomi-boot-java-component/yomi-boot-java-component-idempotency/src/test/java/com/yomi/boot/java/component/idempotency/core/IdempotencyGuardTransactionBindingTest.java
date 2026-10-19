package com.yomi.boot.java.component.idempotency.core;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.idempotency.IdempotencyDataSources;
import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;
import com.yomi.boot.java.component.idempotency.exception.IdempotencyErrorCode;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.store.IdempotencyCacheLoad;
import com.yomi.boot.java.component.idempotency.store.IdempotencyKeys;
import com.yomi.boot.java.component.idempotency.store.IdempotencyRedisStore;
import com.yomi.boot.java.component.idempotency.testsupport.StubDataSource;
import com.yomi.boot.java.component.idempotency.testsupport.Tx;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务边界契约：边界由调用方给，记录库跟着事务走。
 * <ol>
 *   <li>调用点有 {@code @Transactional}（真实切面）→ 沿用这条事务，记录写它绑的库；</li>
 *   <li>没有事务也没传模板 → 直接失败（组件不自建事务：各自自动提交会让失败的键卡在处理中）；</li>
 *   <li>调用方自己用 TransactionTemplate 开的事务 → 拿不到事务状态（保存点靠它），要求把模板交给幂等；</li>
 *   <li>传入的模板必须是 REQUIRED、非只读、DataSourceTransactionManager；</li>
 *   <li>线程上还绑着别的库的事务 → 失败（记录会另起一条事务，外层回滚撤不回它）；</li>
 *   <li>事务落在未注册的库 → 失败并给注册门面的出路；注册过的附加库则记录写它自己的表。</li>
 * </ol>
 * 全离线：数据源为桩（真事务管理器只做绑定），仓储为动态代理桩（查询次数即"记录写了哪个库"的证据）。
 */
class IdempotencyGuardTransactionBindingTest {

    /** 主库：沿用调用点事务（真实 @Transactional 切面）+ 无模板 → 正常重放 */
    @Test
    void ambientTransactionalCallReplaysOnItsOwnLibrary() {
        Fixture fixture = new Fixture();

        Object result = Tx.inTransactional(fixture.masterTxManager, () -> fixture.guard()
                .executeInternal(new IdempotencyKey("ns", "k", null), String.class, null, () -> "should-not-run"));

        assertEquals("ok", result, "调用点事务内照常重放已完成结果");
        assertEquals(1, fixture.master.lookups.get(), "记录必须读主库那份表");
    }

    /** 没有事务也没传模板：直接失败，业务不执行 */
    @Test
    void throwsWhenNoTransactionAndNoTemplate() {
        Fixture fixture = new Fixture();
        AtomicInteger runs = new AtomicInteger();

        BizException ex = assertThrows(BizException.class, () -> fixture.guard()
                .executeInternal(new IdempotencyKey("ns", "k", null), String.class, null, () -> {
                    runs.incrementAndGet();
                    return "should-not-run";
                }));

        assertEquals(IdempotencyErrorCode.TRANSACTION_REQUIRED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("@Transactional"), "报错要给两条出路: " + ex.getMessage());
        assertEquals(0, runs.get(), "没有边界必须拦在业务之前");
    }

    /** 调用方自己用 TransactionTemplate 开的事务：活跃但拿不到事务状态 → 要求显式传模板 */
    @Test
    void throwsWhenAmbientTransactionIsNotAspectManaged() {
        Fixture fixture = new Fixture();

        BizException ex = assertThrows(BizException.class, () -> Tx.inProgrammatic(fixture.masterTxManager,
                () -> fixture.guard().executeInternal(new IdempotencyKey("ns", "k", null), String.class, null,
                        () -> "should-not-run")));

        assertEquals(IdempotencyErrorCode.TRANSACTION_REQUIRED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("TransactionTemplate"), "要指路传模板: " + ex.getMessage());
    }

    /** 传进来的模板必须是 REQUIRED 传播（REQUIRES_NEW 会让记录脱离调用点事务） */
    @Test
    void throwsWhenTemplateIsNotRequired() {
        Fixture fixture = new Fixture();
        TransactionTemplate requiresNew = new TransactionTemplate(fixture.masterTxManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        BizException ex = assertThrows(BizException.class, () -> fixture.guard()
                .executeInternal(new IdempotencyKey("ns", "k", null), String.class, requiresNew,
                        () -> "should-not-run"));

        assertEquals(IdempotencyErrorCode.TRANSACTION_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("REQUIRES_NEW"), ex.getMessage());
    }

    /** 只读模板写不了占位记录 */
    @Test
    void throwsWhenTemplateIsReadOnly() {
        Fixture fixture = new Fixture();
        TransactionTemplate readOnly = new TransactionTemplate(fixture.masterTxManager);
        readOnly.setReadOnly(true);

        BizException ex = assertThrows(BizException.class, () -> fixture.guard()
                .executeInternal(new IdempotencyKey("ns", "k", null), String.class, readOnly, () -> "should-not-run"));

        assertEquals(IdempotencyErrorCode.TRANSACTION_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("只读"), ex.getMessage());
    }

    /** 线程上还绑着别的库的事务：记录会另起一条、外层回滚撤不回 → 失败并点名两个库 */
    @Test
    void throwsWhenAnotherLibraryTransactionIsBound() {
        Fixture fixture = new Fixture();
        fixture.register(Fixture.REPORT, fixture.reportDataSource, fixture.report);

        BizException ex = assertThrows(BizException.class, () -> Tx.inTransactional(fixture.masterTxManager,
                () -> fixture.guard().executeInternal(new IdempotencyKey("ns", "k", null), String.class,
                        fixture.reportTemplate(), () -> "should-not-run")));

        assertEquals(IdempotencyErrorCode.FOREIGN_TRANSACTION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(Fixture.REPORT), "要点名记录库: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(StubDataSource.class.getSimpleName()), "要点名冲突的库: " + ex.getMessage());
    }

    /** 事务落在没注册的库：失败并给注册门面的出路 */
    @Test
    void throwsWhenLibraryIsNotRegistered() {
        Fixture fixture = new Fixture();

        BizException ex = assertThrows(BizException.class, () -> Tx.inTransactional(fixture.reportTxManager,
                () -> fixture.guard().executeInternal(new IdempotencyKey("ns", "k", null), String.class, null,
                        () -> "should-not-run")));

        assertEquals(IdempotencyErrorCode.DATASOURCE_UNREGISTERED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("IdempotencyDataSources"), "要给出路: " + ex.getMessage());
        assertEquals(0, fixture.master.lookups.get(), "不得偷偷写到已注册的别的库");
    }

    /** 注册过的附加库：记录写它自己那份表（模板指明边界） */
    @Test
    void registeredAdditionalLibraryKeepsItsOwnRecords() {
        Fixture fixture = new Fixture();
        fixture.register(Fixture.REPORT, fixture.reportDataSource, fixture.report);

        Object result = fixture.guard().executeInternal(new IdempotencyKey("ns", "k", null), String.class,
                fixture.reportTemplate(), () -> "should-not-run");

        assertEquals("ok", result);
        assertEquals(1, fixture.report.lookups.get(), "记录必须读附加库那份表");
        assertEquals(0, fixture.master.lookups.get(), "不得再碰主库");
    }

    /** 一次调用的桩集合：主库 + 可选附加库，各自的数据源、真实事务管理器与记账仓储 */
    static class Fixture {

        static final String REPORT = "report";

        final StubDataSource masterDataSource = new StubDataSource();
        final DataSourceTransactionManager masterTxManager = new DataSourceTransactionManager(masterDataSource);
        final StubDataSource reportDataSource = new StubDataSource();
        final DataSourceTransactionManager reportTxManager = new DataSourceTransactionManager(reportDataSource);
        final CountingRepository master = new CountingRepository();
        final CountingRepository report = new CountingRepository();
        final IdempotencyDataSources dataSources = new IdempotencyDataSources();

        Fixture() {
            register(IdempotencyDataSources.MASTER, masterDataSource, master);
        }

        void register(String name, StubDataSource dataSource, CountingRepository repository) {
            dataSources.register(name, dataSource, repository.asRepository());
        }

        TransactionTemplate reportTemplate() {
            return new TransactionTemplate(reportTxManager);
        }

        IdempotencyGuard guard() {
            return new BusyGuard(dataSources, new StubRedisStore());
        }
    }

    /** 守卫桩：只覆盖取锁（锁忙 → 读缓存/库重放），不触 Redis */
    static class BusyGuard extends IdempotencyGuard {

        BusyGuard(IdempotencyDataSources dataSources, IdempotencyRedisStore store) {
            super(dataSources, new IdempotencyKeys("yomi:test:idem"), store, new IdempotencyProperties());
        }

        @Override
        java.util.concurrent.locks.Lock acquireLock(String lockKey) {
            throw new com.yomi.boot.java.component.redis.exception.LockBusyException(lockKey);
        }
    }

    /** 记账仓储桩：查询次数即"记录读了哪个库"的证据；库里始终有一条已完成记录 */
    static class CountingRepository {

        final AtomicInteger lookups = new AtomicInteger();

        IIdempotencyRecordRepository asRepository() {
            return (IIdempotencyRecordRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IIdempotencyRecordRepository.class}, (proxy, method, args) ->
                            switch (method.getReturnType().getName()) {
                                case "boolean" -> false;
                                case "int" -> 0;
                                case "long" -> 0L;
                                default -> "findByNamespaceAndKey".equals(method.getName())
                                        ? completed((String) args[0], (String) args[1]) : null;
                            });
        }

        /** 库中已完成记录 */
        private IdempotencyRecord completed(String namespace, String idempotencyKey) {
            lookups.incrementAndGet();
            IdempotencyRecord row = new IdempotencyRecord();
            row.setId(1L);
            row.setNamespace(namespace);
            row.setIdempotencyKey(idempotencyKey);
            row.setStatus(IdempotencyStatus.COMPLETED);
            row.setResultPayload("\"ok\"");
            row.setResultType(String.class.getName());
            return row;
        }
    }

    /** 缓存桩：永远 miss（走库内重放路径） */
    static class StubRedisStore extends IdempotencyRedisStore {

        StubRedisStore() {
            super(null, null);
        }

        @Override
        public IdempotencyCacheLoad load(IdempotencyKeys dsKeys, IdempotencyKey key, Type type) {
            return IdempotencyCacheLoad.miss();
        }
    }

}
