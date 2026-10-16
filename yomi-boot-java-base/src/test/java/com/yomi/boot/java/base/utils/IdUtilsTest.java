package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 雪花ID：位段结构、唯一性、单调性、重复初始化防护、未初始化即失败
 */
class IdUtilsTest {

    @BeforeAll
    static void init() {
        IdUtils.initialize(1L, 2L);
    }

    @Test
    void nextIdUniqueAndStrictlyIncreasing() {
        long prev = -1L;
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = IdUtils.nextId();
            assertTrue(id > prev, "id 必须严格递增: " + id + " <= " + prev);
            assertTrue(ids.add(id), "id 重复: " + id);
            prev = id;
        }
    }

    @Test
    void idEmbedsWorkerIdAndDatacenterId() {
        long id = IdUtils.nextId();
        // 位布局: 时间戳(高位) | datacenterId(5位) | workerId(5位) | sequence(12位)
        assertEquals(1L, (id >> 12) & 0x1F);
        assertEquals(2L, (id >> 17) & 0x1F);
    }

    @Test
    void nextIdStrIsNumeric() {
        long parsed = Long.parseLong(IdUtils.nextIdStr());
        assertTrue(parsed > 0);
    }

    @Test
    void initializeIsIdempotentForSameParams() {
        assertDoesNotThrow(() -> IdUtils.initialize(1L, 2L));
    }

    @Test
    void initializeWithDifferentParamsThrows() {
        assertThrows(UtilsException.class, () -> IdUtils.initialize(2L, 2L));
        assertThrows(UtilsException.class, () -> IdUtils.initialize(1L, 3L));
    }

    @Test
    void initializeOutOfRangeThrows() {
        UtilsException worker = assertThrows(UtilsException.class, () -> IdUtils.initialize(32L, 0L));
        assertTrue(worker.getMessage().contains("yomi.id.worker-id"), "worker 越界信息须指向配置项");
        assertThrows(UtilsException.class, () -> IdUtils.initialize(-1L, 0L));
        assertThrows(UtilsException.class, () -> IdUtils.initialize(0L, 32L));
    }

    @Test
    void nextIdWithoutInitializeThrows() throws Throwable {
        withResetInstance(() -> {
            UtilsException e = assertThrows(UtilsException.class, IdUtils::nextId);
            assertTrue(e.getMessage().contains("yomi.id.worker-id"), "未初始化错误须指向配置项");
        });
    }

    /** 在"清空雪花单例"的前提下执行断言，结束后恢复原状态 */
    private static void withResetInstance(Executable assertion) throws Throwable {
        Field instanceField = IdUtils.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object savedInstance = instanceField.get(null);
        try {
            instanceField.set(null, null);
            assertion.execute();
        } finally {
            instanceField.set(null, savedInstance);
        }
    }

}
