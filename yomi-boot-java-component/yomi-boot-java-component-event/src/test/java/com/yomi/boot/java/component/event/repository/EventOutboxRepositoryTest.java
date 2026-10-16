package com.yomi.boot.java.component.event.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yomi.boot.java.component.event.MybatisLambdaCache;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发件箱仓储的语义回归锁：原 {@code EventOutboxMapper.xml} 的 markFetched / selectFetchedTimeout /
 * resetFetchedToPending 已改由 LambdaWrapper 表达，这里锁住 SET/WHERE 形状与"受影响行数"驱动的分支。
 */
class EventOutboxRepositoryTest {

    private static final Instant FETCH_BEFORE = Instant.parse("2026-10-15T01:00:00Z");

    @BeforeAll
    static void registerEntities() {
        MybatisLambdaCache.register(EventOutbox.class);
    }

    /** 抢占：SET 三字段，WHERE 必须带 from 状态守卫 + 未删除 + id IN；抢占成功在内存态回填状态与抓取时间 */
    @Test
    void fetchAndMarkMarksFetchedWithStatusGuard() {
        Stub stub = new Stub().pendingRows(row(1L), row(2L)).updateRows(2);
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());

        List<EventOutbox> marked = repository.fetchAndMark("svc", 10);

        assertEquals(1, stub.updates.size(), "抢占应只下发一条批量 UPDATE");
        LambdaUpdateWrapper<EventOutbox> update = stub.updates.get(0);
        String set = compact(update.getSqlSet());
        assertTrue(set.contains("status=#{"), set);
        assertTrue(set.contains("fetch_time=#{"), set);
        assertTrue(set.contains("update_time=#{"), set);
        String where = compact(update.getSqlSegment());
        assertTrue(where.contains("status=#{"), where);
        assertTrue(where.contains("delete_timeisnull"), where);
        assertTrue(where.contains("idin("), where);
        assertTrue(update.getParamNameValuePairs().values().contains(OutboxStatus.FETCHED), "SET 目标状态");
        assertTrue(update.getParamNameValuePairs().values().contains(OutboxStatus.PENDING), "WHERE 状态守卫");

        assertEquals(List.of(1L, 2L), marked.stream().map(EventOutbox::getId).toList());
        assertEquals(OutboxStatus.FETCHED, marked.get(0).getStatus());
        assertEquals(0, marked.get(0).getFetchTime().getNano() % 1_000_000,
                "fetch_time 必须与 datetime(3) 对齐到毫秒（否则按归属重查匹配不上）");
        assertTrue(stub.selectListWrappers.isEmpty(), "全部抢占成功不应回查");
    }

    /** 一行都没抢到：直接返回空，不回查 */
    @Test
    void fetchAndMarkReturnsEmptyWhenNothingClaimed() {
        Stub stub = new Stub().pendingRows(row(1L)).updateRows(0);
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());

        assertTrue(repository.fetchAndMark("svc", 10).isEmpty());
        assertTrue(stub.selectListWrappers.isEmpty());
    }

    /** 部分抢占：必须按 fetch_time 归属重查，避免把他人已抢占的行当自己的重复投递 */
    @Test
    void fetchAndMarkReQueriesOwnRowsWhenPartiallyClaimed() {
        Stub stub = new Stub().pendingRows(row(1L), row(2L)).updateRows(1)
                .selectListResult(List.of(row(1L)));
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());

        List<EventOutbox> marked = repository.fetchAndMark("svc", 10);

        assertEquals(List.of(1L), marked.stream().map(EventOutbox::getId).toList());
        assertEquals(1, stub.selectListWrappers.size(), "部分抢占必须按归属重查");
        String where = compact(stub.selectListWrappers.get(0).getSqlSegment());
        assertTrue(where.contains("fetch_time=#{"), where);
        assertTrue(where.contains("status=#{"), where);
        assertTrue(where.contains("idin("), where);
        assertTrue(where.contains("delete_timeisnull"), where);
    }

    /** 取批：一页 + 不要 count + 按 create_time 升序（上限靠分页拦截器落到 SQL 的 LIMIT） */
    @Test
    void fetchPendingTakesOneBoundedPageOrderedByCreateTime() {
        Stub stub = new Stub().pendingRows(row(1L));
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());

        List<EventOutbox> rows = repository.fetchPending("svc", 500);

        assertEquals(List.of(1L), rows.stream().map(EventOutbox::getId).toList());
        assertEquals(1, stub.pendingPages.size(), "取批只查一次");
        assertEquals(500, stub.pendingPages.get(0).getSize(), "批量上限即页大小");
        assertFalse(stub.pendingPages.get(0).searchCount(), "只取一页，不要 count 查询");
        String where = compact(stub.pendingWrappers.get(0).getSqlSegment());
        assertTrue(where.contains("service_name=#{"), where);
        assertTrue(where.contains("status=#{"), where);
        assertTrue(where.contains("delete_timeisnull"), where);
        assertTrue(where.endsWith("orderbycreate_timeasc"), where);
    }

    /** 超时回收查询：service_name + FETCHED + fetch_time < 阈值 + 未删除；一页 + 不要 count + 按 id 升序 */
    @Test
    void findFetchedTimeoutFiltersByStatusAndFetchTimeBefore() {
        Stub stub = new Stub().pendingRows(row(1L));
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());

        List<EventOutbox> rows = repository.findFetchedTimeout("svc", FETCH_BEFORE, 500);

        assertEquals(List.of(1L), rows.stream().map(EventOutbox::getId).toList());
        assertEquals(1, stub.pendingPages.size(), "回收只查一页");
        // 回收批量即页大小：积压几万行时不会被一次性全量读进内存
        assertEquals(500, stub.pendingPages.get(0).getSize(), "单批上限即页大小");
        assertFalse(stub.pendingPages.get(0).searchCount(), "只取一页，不要 count 查询");
        LambdaQueryWrapper<EventOutbox> query = stub.pendingWrappers.get(0);
        String where = compact(query.getSqlSegment());
        assertTrue(where.contains("service_name=#{"), where);
        assertTrue(where.contains("status=#{"), where);
        assertTrue(where.contains("fetch_time<#{"), where);
        assertTrue(where.contains("delete_timeisnull"), where);
        assertTrue(where.endsWith("orderbyidasc"), where);
        assertTrue(query.getParamNameValuePairs().values().contains(OutboxStatus.FETCHED));
        assertTrue(query.getParamNameValuePairs().values().contains(FETCH_BEFORE));
    }

    /** 超时回收重置：清 fetch_time（显式 NULL）+ 状态回 PENDING + 状态守卫；空入参不触库 */
    @Test
    void resetFetchedBatchClearsFetchTimeAndResetsStatus() {
        Stub stub = new Stub();
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());

        repository.resetFetchedBatch(List.of(1L, 2L));

        assertEquals(1, stub.updates.size());
        LambdaUpdateWrapper<EventOutbox> update = stub.updates.get(0);
        String set = compact(update.getSqlSet());
        assertTrue(set.contains("status=#{"), set);
        assertTrue(set.contains("fetch_time=#{"), set);
        assertTrue(set.contains("update_time=#{"), set);
        assertTrue(update.getParamNameValuePairs().values().contains(null), "fetch_time 必须显式写 NULL");
        assertTrue(update.getParamNameValuePairs().values().contains(OutboxStatus.PENDING));
        String where = compact(update.getSqlSegment());
        assertTrue(where.contains("status=#{"), where);
        assertTrue(where.contains("delete_timeisnull"), where);
        assertTrue(where.contains("idin("), where);

        Stub empty = new Stub();
        new EventOutboxRepository(empty.mapper()).resetFetchedBatch(List.of());
        assertTrue(empty.updates.isEmpty(), "空入参不应触库");
    }

    /** 大 id 列表必须分片下发：单条 IN 的绑定参数会撞上 PG 65535 上限（MySQL 则是超长语句） */
    @Test
    void resetFetchedBatchChunksLargeIdLists() {
        Stub stub = new Stub();
        EventOutboxRepository repository = new EventOutboxRepository(stub.mapper());
        List<Long> ids = LongStream.rangeClosed(1, 2_500).boxed().toList();

        repository.resetFetchedBatch(ids);

        assertEquals(3, stub.updates.size(), "2500 个 id 按 1000 一片下发");
        assertEquals(List.of(1_000, 1_000, 500), stub.updates.stream()
                .map(update -> inListSize(update.getSqlSegment())).toList(), "每片 IN 的元素个数");
    }

    // ===== 夹具 =====

    /** SQL 片段比较前压掉空白并统一小写，避免断言绑在 MP 的排版/大小写细节上 */
    private static String compact(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 数出 IN 列表的元素个数（MP 把 in 展开为逐项占位符，逗号数 + 1） */
    private static int inListSize(String sqlSegment) {
        String compacted = compact(sqlSegment);
        int start = compacted.indexOf("idin(");
        int end = compacted.indexOf(')', start);
        String inside = compacted.substring(start, end);
        return (int) inside.chars().filter(ch -> ch == ',').count() + 1;
    }

    private static EventOutbox row(long id) {
        EventOutbox row = new EventOutbox();
        row.setId(id);
        row.setServiceName("svc");
        row.setEventId("evt-" + id);
        row.setStatus(OutboxStatus.PENDING);
        return row;
    }

    /** 记录型 Mapper 代理：可编排抢占行数与查询结果 */
    private static final class Stub {

        private final List<EventOutbox> pendingRows = new ArrayList<>();
        private final List<IPage<EventOutbox>> pendingPages = new ArrayList<>();
        private final List<LambdaQueryWrapper<EventOutbox>> pendingWrappers = new ArrayList<>();
        private final List<LambdaUpdateWrapper<EventOutbox>> updates = new ArrayList<>();
        private final List<LambdaQueryWrapper<EventOutbox>> selectListWrappers = new ArrayList<>();
        private List<EventOutbox> selectListResult = List.of();
        private int updateRows = 1;

        Stub pendingRows(EventOutbox... rows) {
            pendingRows.addAll(List.of(rows));
            return this;
        }

        Stub updateRows(int rows) {
            this.updateRows = rows;
            return this;
        }

        Stub selectListResult(List<EventOutbox> rows) {
            this.selectListResult = rows;
            return this;
        }

        @SuppressWarnings("unchecked")
        EventOutboxMapper mapper() {
            return (EventOutboxMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{EventOutboxMapper.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "selectPage" -> {
                            IPage<EventOutbox> page = (IPage<EventOutbox>) args[0];
                            pendingPages.add(page);
                            pendingWrappers.add((LambdaQueryWrapper<EventOutbox>) args[args.length - 1]);
                            page.setRecords(List.copyOf(pendingRows));
                            yield page;
                        }
                        case "update" -> {
                            updates.add((LambdaUpdateWrapper<EventOutbox>) args[1]);
                            yield updateRows;
                        }
                        case "selectList" -> {
                            // 只覆盖单参重载（IPage 重载本用例不用）
                            if (args.length == 1) {
                                selectListWrappers.add((LambdaQueryWrapper<EventOutbox>) args[0]);
                            }
                            yield selectListResult;
                        }
                        default -> defaultReturn(proxy, method, args);
                    });
        }

        private static Object defaultReturn(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "stub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }
    }

}
