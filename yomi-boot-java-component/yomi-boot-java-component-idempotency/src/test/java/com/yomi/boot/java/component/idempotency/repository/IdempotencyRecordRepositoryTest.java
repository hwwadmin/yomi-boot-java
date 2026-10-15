package com.yomi.boot.java.component.idempotency.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yomi.boot.java.base.exception.AssertException;
import com.yomi.boot.java.component.idempotency.core.IdempotencyStatus;
import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;
import com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等记录仓储的语义回归锁：原 {@code IdempotencyRecordMapper.xml} 的 markCompleted 已改由
 * LambdaUpdateWrapper 表达，这里锁住"处理中 → 已完成 + 结果快照 + 行数==1 才通过"的语义。
 */
class IdempotencyRecordRepositoryTest {

    @BeforeAll
    static void registerEntity() {
        // 离线单测无 MyBatis 启动：LambdaWrapper 解析列名依赖 MP 的实体缓存，需手工注册
        if (TableInfoHelper.getTableInfo(IdempotencyRecord.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    IdempotencyRecord.class);
        }
    }

    /** SET 写状态与结果快照，WHERE 带"处理中"守卫 + 未删除；行数非 1 视为未生效 */
    @Test
    void markCompletedWritesStatusAndResultUnderProcessingGuard() {
        Stub stub = new Stub();
        IdempotencyRecordRepository repository = new IdempotencyRecordRepository(stub.mapper());

        repository.markCompleted(9L, "java.lang.String", "\"ok\"");

        assertEquals(1, stub.updates.size());
        LambdaUpdateWrapper<IdempotencyRecord> update = stub.updates.get(0);
        String set = compact(update.getSqlSet());
        assertTrue(set.contains("status=#{"), set);
        assertTrue(set.contains("result_type=#{"), set);
        assertTrue(set.contains("result_payload=#{"), set);
        assertTrue(set.contains("update_time=#{"), set);
        String where = compact(update.getSqlSegment());
        assertTrue(where.contains("id=#{"), where);
        assertTrue(where.contains("status=#{"), where);
        assertTrue(where.contains("delete_timeisnull"), where);
        List<Object> values = List.copyOf(update.getParamNameValuePairs().values());
        assertTrue(values.contains(IdempotencyStatus.COMPLETED), "SET 目标状态");
        assertTrue(values.contains(IdempotencyStatus.PROCESSING), "WHERE 状态守卫");
        assertTrue(values.contains("java.lang.String"));

        stub.updateRows = 0;
        assertThrows(AssertException.class, () -> repository.markCompleted(9L, "java.lang.String", "\"ok\""));
    }

    /** 业务返回空结果时必须显式写 NULL（不能因空值而漏掉 SET，否则残留上一轮结果） */
    @Test
    void markCompletedWritesExplicitNullResult() {
        Stub stub = new Stub();
        IdempotencyRecordRepository repository = new IdempotencyRecordRepository(stub.mapper());

        repository.markCompleted(9L, null, null);

        LambdaUpdateWrapper<IdempotencyRecord> update = stub.updates.get(0);
        assertTrue(update.getParamNameValuePairs().values().contains(null), "空结果必须显式写 NULL");
    }

    // ===== 夹具 =====

    /** SQL 片段比较前压掉空白并统一小写，避免断言绑在 MP 的排版/大小写细节上 */
    private static String compact(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 记录型 Mapper 代理：只关心两参 update 的 wrapper 与返回行数 */
    private static final class Stub {

        private final List<LambdaUpdateWrapper<IdempotencyRecord>> updates = new ArrayList<>();
        private int updateRows = 1;

        @SuppressWarnings("unchecked")
        IdempotencyRecordMapper mapper() {
            return (IdempotencyRecordMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IdempotencyRecordMapper.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "update" -> {
                            updates.add((LambdaUpdateWrapper<IdempotencyRecord>) args[1]);
                            yield updateRows;
                        }
                        case "toString" -> "stub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    });
        }
    }

}
