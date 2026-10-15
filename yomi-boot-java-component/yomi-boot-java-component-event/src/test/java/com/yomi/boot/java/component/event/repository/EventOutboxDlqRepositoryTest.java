package com.yomi.boot.java.component.event.repository;

import com.yomi.boot.java.base.exception.AssertException;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxDlqMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DLQ 仓储：扫描拆成"未排期 + 已到期"两条查询后的合并顺序与 limit 分摊、运维复位的参数校验，
 * 以及 mapper XML 不再出现 COALESCE 排序表达式（索引可用性回归锁）。
 */
class EventOutboxDlqRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-14T02:00:00Z");
    private static final String MAPPER_XML = "mapper/EventOutboxDlqMapper.xml";

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
        assertEquals(List.of("selectRetryableUnscheduled", "selectRetryableDue"), mapper.calls);
        assertEquals(9, mapper.dueLimit, "到期查询的 limit 用剩余额度，总量不超过 limit");
    }

    /** 未排期段已用满额度：不再查到期的（总量仍受 limit 约束） */
    @Test
    void fetchRetryableSkipsDueQueryWhenLimitExhausted() {
        FakeMapper mapper = new FakeMapper();
        mapper.unscheduled = List.of(dlq(1L, null, "2026-10-14T01:00:00Z"), dlq(2L, null, "2026-10-14T01:10:00Z"));
        mapper.due = List.of(dlq(3L, "2026-10-14T00:30:00Z", "2026-10-13T22:00:00Z"));
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(mapper.stub());

        List<EventOutboxDlq> rows = repository.fetchRetryable("test", 30, 2, NOW);

        assertEquals(List.of(1L, 2L), rows.stream().map(EventOutboxDlq::getId).toList());
        assertEquals(List.of("selectRetryableUnscheduled"), mapper.calls);
    }

    /** 非法 limit 仍要拦（沿用原断言） */
    @Test
    void fetchRetryableRejectsNonPositiveLimit() {
        EventOutboxDlqRepository repository = new EventOutboxDlqRepository(new FakeMapper().stub());

        assertThrows(AssertException.class, () -> repository.fetchRetryable("test", 30, 0, NOW));
    }

    /** 运维复位：from/to 为空必须抛业务异常（不再让 NPE 逃逸到调用方），且不触库 */
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

    /** mapper XML 回归锁：排序里不得再有表达式（COALESCE 用不上索引，大表每轮全表排序） */
    @Test
    void mapperXmlKeepsScanOrderIndexFriendly() throws IOException {
        String xml = readMapperXml();

        assertTrue(xml.contains("id=\"selectRetryableUnscheduled\""), MAPPER_XML);
        assertTrue(xml.contains("id=\"selectRetryableDue\""), MAPPER_XML);
        List<String> orderBys = xml.lines().map(String::trim)
                .filter(line -> line.startsWith("ORDER BY")).toList();
        assertEquals(List.of("ORDER BY create_time ASC", "ORDER BY retry_after_time ASC, create_time ASC"),
                orderBys, "两段各自按索引列排序，不得用表达式");
        orderBys.forEach(line -> assertFalse(line.contains("("), "排序不得含表达式: " + line));
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

    /** 假 Mapper：记录调用顺序与分摊到的 limit */
    static class FakeMapper {

        final List<String> calls = new ArrayList<>();
        List<EventOutboxDlq> unscheduled = List.of();
        List<EventOutboxDlq> due = List.of();
        int dueLimit;

        EventOutboxDlqMapper stub() {
            return (EventOutboxDlqMapper) Proxy.newProxyInstance(
                    EventOutboxDlqMapper.class.getClassLoader(), new Class<?>[]{EventOutboxDlqMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectRetryableUnscheduled" -> {
                            calls.add("selectRetryableUnscheduled");
                            yield unscheduled;
                        }
                        case "selectRetryableDue" -> {
                            calls.add("selectRetryableDue");
                            dueLimit = (int) args[3];
                            yield due;
                        }
                        case "resetRetryCountInRange" -> {
                            calls.add("resetRetryCountInRange");
                            yield 1;
                        }
                        default -> null;
                    });
        }
    }

    private static String readMapperXml() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MAPPER_XML)) {
            assertTrue(in != null, "缺少 " + MAPPER_XML);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
