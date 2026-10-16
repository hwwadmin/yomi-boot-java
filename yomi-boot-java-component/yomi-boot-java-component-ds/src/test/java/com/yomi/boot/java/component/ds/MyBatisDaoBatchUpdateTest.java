package com.yomi.boot.java.component.ds;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.base.utils.IdUtils;
import org.apache.ibatis.executor.BatchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量更新契约：id 不存在的行静默跳过（JDBC 批量受影响行数为 0，不抛错），
 * 与单条 update()/saveOrUpdate() 的"未命中即失败"不同——契约写在 javadoc 里，本类锁住它，
 * 防止把批量路径改成"抛错/补插"时无人察觉。
 */
class MyBatisDaoBatchUpdateTest {

    @Test
    void updateBatchSkipsMissingIdsSilently() {
        var calls = new ArrayList<String>();
        var dao = new DemoDao(mapper(calls));

        Demo a = new Demo();
        a.setId(11L);
        Demo b = new Demo();
        b.setId(12L);

        assertDoesNotThrow(() -> dao.updateBatch(List.of(a, b)),
                "受影响行数全为 0（id 均不存在）时批量更新必须静默跳过，不得抛错");

        assertEquals(List.of("updateById:2"), calls, "必须走 MP 原生 updateById(Collection) 的单次批量调用");
        assertNotNull(a.getUpdateTime(), "批量更新必须先执行 entity.update()");
        assertNotNull(b.getUpdateTime());
    }

    @Test
    void updateBatchOfEmptyListDoesNotTouchMapper() {
        var calls = new ArrayList<String>();
        new DemoDao(mapper(calls)).updateBatch(List.of());
        assertTrue(calls.isEmpty(), "空集合不得访问 Mapper");
    }

    @Test
    void saveOrUpdateBatchGroupsInsertsAndUpdatesAndNeverBackfills() {
        IdUtils.initialize(1L, 0L);
        var calls = new ArrayList<String>();
        var dao = new DemoDao(mapper(calls));

        Demo created = new Demo();
        Demo missing = new Demo();
        missing.setId(22L);

        assertDoesNotThrow(() -> dao.saveOrUpdateBatch(List.of(created, missing)),
                "更新未命中（id 不存在）必须静默跳过，不得抛错");

        assertEquals(List.of("insert:1", "updateById:1"), calls, "新增与更新各一次批量 SQL，未命中不得补插");
        assertNotNull(created.getId(), "新增分支必须 init 出主键");
        assertNotNull(missing.getUpdateTime(), "更新分支必须先执行 entity.update()");
    }

    /** 伪 Mapper：记录调用；updateById 一律返回"受影响行数全 0"（模拟所有 id 都不存在） */
    @SuppressWarnings("unchecked")
    private static BaseMapper<Demo> mapper(List<String> calls) {
        return (BaseMapper<Demo>) Proxy.newProxyInstance(MyBatisDaoBatchUpdateTest.class.getClassLoader(),
                new Class<?>[]{BaseMapper.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "updateById" -> {
                        calls.add("updateById:" + ((Collection<?>) args[0]).size());
                        yield batchResult(((Collection<?>) args[0]).size(), 0);
                    }
                    case "insert" -> {
                        calls.add("insert:" + ((Collection<?>) args[0]).size());
                        yield batchResult(((Collection<?>) args[0]).size(), 1);
                    }
                    case "toString" -> "stub-mapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> false;
                    default -> throw new IllegalStateException("未预期的 Mapper 调用: " + method.getName());
                });
    }

    private static List<BatchResult> batchResult(int size, int updateCount) {
        var result = new BatchResult(null, "batch");
        int[] counts = new int[size];
        Arrays.fill(counts, updateCount);
        result.setUpdateCounts(counts);
        return List.of(result);
    }

    static class Demo extends BaseEntity {
    }

    static class DemoDao extends MyBatisDao<BaseMapper<Demo>, Demo> {
        DemoDao(BaseMapper<Demo> mapper) {
            super(mapper);
        }
    }
}
