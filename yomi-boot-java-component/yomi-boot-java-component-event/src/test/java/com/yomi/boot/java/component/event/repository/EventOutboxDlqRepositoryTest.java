package com.yomi.boot.java.component.event.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yomi.boot.java.base.exception.AssertException;
import com.yomi.boot.java.component.event.MybatisLambdaCache;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxDlqMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DLQ 仓储：扫描分"未排期 + 已到期"两段（由 LambdaQueryWrapper + MP Page 表达，上限由分页拦截器落到
 * LIMIT），验证两段的合并顺序、limit 分摊与索引友好排序；以及运维复位与 in-flight 认领的参数/行数语义。
 */
class EventOutboxDlqRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-14T02:00:00Z");

    @BeforeAll
    static void registerEntities() {
        MybatisLambdaCache.register(EventOutboxDlq.class);
    }

    /** 两段合并后按"就绪时间"（retry_after_time，未排期取 create_time）升序 */
    @Test
    void fetchRetryableMergesBothSegmentsByReadyTime() {
        FakeMapper mapper = new FakeMapper();
        mapper.unscheduled = List.of(dlq(1L, null, "2026-10-14T01:00:00Z"));
        mapper.due = List.of(
                dlq(2L, "2026-10-14T00:30:00Z", "2026-10-13T22:00:00Z"),
                dlq(3L, "2026-10-14T01:30:00Z", "2026-10-13T23:00:00Z"));
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(mapper.stub());

        List<EventOutboxDlq> rows = repository.fetchRetryable("test", 30, 10, NOW);

        assertEquals(List.of(2L, 1L, 3L), rows.stream().map(EventOutboxDlq::getId).toList(),
                "必须按就绪时间升序（等价于原 ORDER BY COALESCE(retry_after_time, create_time)）");
        assertEquals(List.of("select:unscheduled", "select:due"), mapper.calls);
        assertEquals(List.of(10L, 9L), mapper.pages.stream().map(IPage::getSize).toList(),
                "到期段的页大小用剩余额度，总量不超过 limit");
        assertTrue(mapper.pages.stream().noneMatch(IPage::searchCount), "只取一页，不要 count 查询");
    }

    /** 未排期段已用满额度：跳过到期段查询（总量仍受 limit 约束） */
    @Test
    void fetchRetryableSkipsDueQueryWhenLimitExhausted() {
        FakeMapper mapper = new FakeMapper();
        mapper.unscheduled = List.of(dlq(1L, null, "2026-10-14T01:00:00Z"), dlq(2L, null, "2026-10-14T01:10:00Z"));
        mapper.due = List.of(dlq(3L, "2026-10-14T00:30:00Z", "2026-10-13T22:00:00Z"));
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(mapper.stub());

        List<EventOutboxDlq> rows = repository.fetchRetryable("test", 30, 2, NOW);

        assertEquals(List.of(1L, 2L), rows.stream().map(EventOutboxDlq::getId).toList());
        assertEquals(List.of("select:unscheduled"), mapper.calls);
    }

    /** 非法 limit 直接拒绝 */
    @Test
    void fetchRetryableRejectsNonPositiveLimit() {
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(new FakeMapper().stub());

        assertThrows(AssertException.class, () -> repository.fetchRetryable("test", 30, 0, NOW));
    }

    /** maxRetry 非正：结果为空且不触库 */
    @Test
    void fetchRetryableReturnsEmptyWhenMaxRetryNotPositive() {
        FakeMapper mapper = new FakeMapper();
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(mapper.stub());

        assertTrue(repository.fetchRetryable("test", 0, 10, NOW).isEmpty());
        assertTrue(mapper.calls.isEmpty(), "未达重试上限的条件恒不成立，不应触库");
    }

    /** 排序只用索引列：两段各自按索引列排序（COALESCE 这类表达式用不上索引，大表每轮会全表排序） */
    @Test
    void segmentOrderStaysIndexFriendly() {
        FakeMapper mapper = new FakeMapper();
        new EventOutboxDlqRepository(mapper.stub()).fetchRetryable("test", 30, 10, NOW);

        List<String> orderBys = mapper.wrappers.stream().map(wrapper -> compact(wrapper.getSqlSegment()))
                .map(sql -> sql.indexOf("orderby") < 0 ? "<无排序>" : sql.substring(sql.indexOf("orderby")))
                .toList();
        assertEquals(List.of("orderbycreate_timeasc", "orderbyretry_after_timeasc,create_timeasc"), orderBys,
                "两段各自按索引列排序，不得用表达式");
        orderBys.forEach(orderBy -> assertFalse(orderBy.contains("("), "排序不得含表达式: " + orderBy));
    }

    /** 两段的过滤条件：未删除 + 未达重试上限 + （未排期 / 已到退避窗口） */
    @Test
    void segmentsFilterByRetryGuardAndWindow() {
        FakeMapper mapper = new FakeMapper();
        new EventOutboxDlqRepository(mapper.stub()).fetchRetryable("test", 30, 10, NOW);

        String unscheduled = compact(mapper.wrappers.get(0).getSqlSegment());
        assertTrue(unscheduled.contains("service_name=#{"), unscheduled);
        assertTrue(unscheduled.contains("delete_timeisnull"), unscheduled);
        assertTrue(unscheduled.contains("retry_count<#{"), unscheduled);
        assertTrue(unscheduled.contains("retry_after_timeisnull"), unscheduled);

        String due = compact(mapper.wrappers.get(1).getSqlSegment());
        assertTrue(due.contains("retry_count<#{"), due);
        assertTrue(due.contains("retry_after_time<=#{"), due);
        assertTrue(due.contains("delete_timeisnull"), due);
    }

    /** 运维复位：from/to 为空抛业务异常，且不触库 */
    @Test
    void resetRetryCountInRangeRejectsNullArgs() {
        FakeMapper mapper = new FakeMapper();
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(mapper.stub());

        assertThrows(AssertException.class, () -> repository.resetRetryCountInRange("test", null, NOW));
        assertThrows(AssertException.class, () -> repository.resetRetryCountInRange("test", NOW, null));
        assertEquals(List.of(), mapper.calls, "参数非法时不得触库");
    }

    @Test
    void resetRetryCountInRangeRejectsReversedRange() {
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(new FakeMapper().stub());

        assertThrows(IllegalArgumentException.class,
                () -> repository.resetRetryCountInRange("test", NOW, NOW.minusSeconds(1)));
    }

    /** in-flight 认领：SET 推退避窗口与更新时间，WHERE 带"可重试"条件；只有更新到恰好 1 行才算认领成功 */
    @Test
    void tryClaimInFlightPushesRetryWindowWithRetryableGuard() {
        FakeMapper mapper = new FakeMapper();
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(mapper.stub());

        assertTrue(repository.tryClaimInFlight(7L, Duration.ofMinutes(5)));

        assertEquals(List.of("update"), mapper.calls);
        String set = compact(mapper.claimWrapper.getSqlSet());
        assertTrue(set.contains("retry_after_time=#{"), set);
        assertTrue(set.contains("update_time=#{"), set);
        String where = compact(mapper.claimWrapper.getSqlSegment());
        assertTrue(where.contains("id=#{"), where);
        assertTrue(where.contains("delete_timeisnull"), where);
        assertTrue(where.contains("retry_after_timeisnull"), where);
        assertTrue(where.contains("retry_after_time<=#{"),
                "已排期但已过退避窗口的行也应可抢占: " + where);

        mapper.claimRows = 0;
        assertFalse(repository.tryClaimInFlight(7L, Duration.ofMinutes(5)), "未更新到行即抢占失败");
    }

    // ===== 夹具 =====

    private static EventOutboxDlq dlq(long id, String retryAfterIso, String createTimeIso) {
        EventOutboxDlq row = new EventOutboxDlq();
        row.setId(id);
        row.setServiceName("test");
        row.setRetryCount(1);
        row.setRetryAfterTime(retryAfterIso == null ? null : Instant.parse(retryAfterIso));
        row.setCreateTime(Instant.parse(createTimeIso));
        return row;
    }

    /** SQL 片段比较前压掉空白并统一小写，断言不绑定 MP 的排版/大小写细节 */
    private static String compact(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 假 Mapper：按 wrapper 的 SQL 片段区分"未排期/已到期"两段，记录 Page 与 wrapper */
    static class FakeMapper {

        final List<String> calls = new ArrayList<>();
        List<EventOutboxDlq> unscheduled = List.of();
        List<EventOutboxDlq> due = List.of();
        final List<IPage<EventOutboxDlq>> pages = new ArrayList<>();
        final List<LambdaQueryWrapper<EventOutboxDlq>> wrappers = new ArrayList<>();
        LambdaUpdateWrapper<EventOutboxDlq> claimWrapper;
        int claimRows = 1;

        @SuppressWarnings("unchecked")
        EventOutboxDlqMapper stub() {
            return (EventOutboxDlqMapper) Proxy.newProxyInstance(
                    EventOutboxDlqMapper.class.getClassLoader(), new Class<?>[]{EventOutboxDlqMapper.class},
                    (proxy, method, args) -> {
                        if (("selectPage".equals(method.getName()) || "selectList".equals(method.getName()))
                                && args != null && args.length >= 1 && args[0] instanceof IPage) {
                            return handleSelect(args);
                        }
                        if ("update".equals(method.getName())) {
                            // in-flight 认领由仓储的 LambdaUpdateWrapper 表达（两参重载），args[0] 为空实体
                            calls.add("update");
                            claimWrapper = (LambdaUpdateWrapper<EventOutboxDlq>) args[1];
                            return claimRows;
                        }
                        if ("resetRetryCountInRange".equals(method.getName())) {
                            calls.add("resetRetryCountInRange");
                            return 1;
                        }
                        return defaults(proxy, method, args);
                    });
        }

        /** 链式 page() 走 selectPage(IPage, Wrapper)：按 wrapper 判定段，回填该段的数据 */
        @SuppressWarnings("unchecked")
        private IPage<EventOutboxDlq> handleSelect(Object[] args) {
            IPage<EventOutboxDlq> page = (IPage<EventOutboxDlq>) args[0];
            LambdaQueryWrapper<EventOutboxDlq> wrapper = (LambdaQueryWrapper<EventOutboxDlq>) args[args.length - 1];
            boolean unscheduledSegment = compact(wrapper.getSqlSegment()).contains("retry_after_timeisnull");
            calls.add("select:" + (unscheduledSegment ? "unscheduled" : "due"));
            pages.add(page);
            wrappers.add(wrapper);
            page.setRecords(unscheduledSegment ? unscheduled : due);
            return page;
        }

        private static Object defaults(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "stub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }
    }

}
