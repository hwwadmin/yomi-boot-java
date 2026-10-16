package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacGroupPermRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacRoleGroupRelMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacUserRoleRelMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 端口写方法的原子性：`IRbacRelationRepository` 是公开端口（宿主可绕过管理服务直接调用），
 * 默认实现整类带 REQUIRED 事务 —— 直接调用时事务自开，"先删后插"期间任一步失败整体回滚，
 * 不会留下"已删未插"的绑定丢失（真实回滚由事务管理器完成，本用例锁定"同一事务 + 失败请求回滚"）
 */
class RbacRelationRepositoryTransactionTest {

    /** 仓储写方法经 BaseEntity.init() 生成雪花主键，测试内自行初始化（不依赖别的用例先跑） */
    @BeforeAll
    static void initIds() {
        IdUtils.initialize(1L, 0L);
    }

    @Test
    void everyWriteMethodRunsInTransactionAndCommits() {
        RecordingMappers mappers = new RecordingMappers();
        RecordingTxManager tx = new RecordingTxManager();
        try (AnnotationConfigApplicationContext context = context(mappers, tx)) {
            IRbacRelationRepository repository = context.getBean(IRbacRelationRepository.class);

            repository.replaceUserRoles(7L, List.of(11L, 12L), 99L);
            assertEquals(List.of("delete:inTx", "insert:inTx", "insert:inTx"), mappers.statements);
            assertEquals(List.of("begin", "commit"), tx.events);

            mappers.statements.clear();
            tx.events.clear();
            repository.replaceRoleGroups(1L, List.of(21L), 99L);
            repository.replaceGroupPerms(2L, List.of(31L), 99L);
            repository.deleteRoleRelations(3L);
            assertEquals(List.of("delete:inTx", "insert:inTx", "delete:inTx", "insert:inTx",
                    "delete:inTx", "delete:inTx"), mappers.statements);
            assertEquals(List.of("begin", "commit", "begin", "commit", "begin", "commit"), tx.events);
        }
    }

    @Test
    void failedInsertRollsBackWholeReplace() {
        RecordingMappers mappers = new RecordingMappers();
        mappers.failInsertAt = 2;
        RecordingTxManager tx = new RecordingTxManager();
        try (AnnotationConfigApplicationContext context = context(mappers, tx)) {
            IRbacRelationRepository repository = context.getBean(IRbacRelationRepository.class);

            assertThrows(IllegalStateException.class, () -> repository.replaceUserRoles(7L, List.of(11L, 12L), 99L));

            assertEquals(List.of("delete:inTx", "insert:inTx", "insert:inTx"), mappers.statements,
                    "删除与插入都必须在同一事务里执行");
            assertEquals(List.of("begin", "rollback"), tx.events, "中途失败必须整体回滚");
        }
    }

    /** `@EnableTransactionManagement` 与 Boot 默认同款（CGLIB 代理）：写方法经代理调用才具备事务语义 */
    private static AnnotationConfigApplicationContext context(RecordingMappers mappers, RecordingTxManager tx) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TxEnabledConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> tx);
        context.registerBean(IRbacRelationRepository.class, () -> mappers.repository());
        context.refresh();
        return context;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TxEnabledConfig {
    }

    /** 记录事务事件（假事务管理器：只记录 begin/commit/rollback，不做真实连接操作） */
    static class RecordingTxManager extends AbstractPlatformTransactionManager {

        final List<String> events = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    /** 代理 Mapper：记录语句与"是否在事务中"，可指定第 N 条 insert 失败 */
    static class RecordingMappers {

        final List<String> statements = new ArrayList<>();
        int failInsertAt = -1;
        private int inserts;

        RbacRelationRepository repository() {
            return new RbacRelationRepository(
                    proxy(RbacUserRoleRelMapper.class), proxy(RbacRoleGroupRelMapper.class),
                    proxy(RbacGroupPermRelMapper.class), new RbacRoleRepository(null));
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(Class<T> type) {
            return (T) Proxy.newProxyInstance(RecordingMappers.class.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "delete" -> {
                            statements.add(flag("delete"));
                            yield 1;
                        }
                        case "insert" -> {
                            statements.add(flag("insert"));
                            if (++inserts == failInsertAt) {
                                throw new IllegalStateException("模拟第 " + failInsertAt + " 条 insert 失败");
                            }
                            yield 1;
                        }
                        default -> null;
                    });
        }

        private static String flag(String statement) {
            return statement + (TransactionSynchronizationManager.isActualTransactionActive() ? ":inTx" : ":noTx");
        }
    }

}
