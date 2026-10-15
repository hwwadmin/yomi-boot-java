package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 雪花ID：位段结构、唯一性、单调性、重复初始化防护
 */
class IdUtilsTest {

    @BeforeAll
    static void init() {
        // 走系统属性解析路径，与 -Dyomi.id.worker-id 等价
        System.setProperty("yomi.id.worker-id", "1");
        System.setProperty("yomi.id.datacenter-id", "2");
        IdUtils.initialize(1L, 2L, null);
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
        assertDoesNotThrow(() -> IdUtils.initialize(1L, 2L, null));
        // workerId 为 null 时走属性自解析，解析结果 1 与已初始化一致
        assertDoesNotThrow(() -> IdUtils.initialize(null, 2L, null));
        // 双 null 视为未配置，直接跳过
        assertDoesNotThrow(() -> IdUtils.initialize(null, null, null));
    }

    @Test
    void initializeWithDifferentParamsThrows() {
        assertThrows(UtilsException.class, () -> IdUtils.initialize(2L, 2L, null));
        assertThrows(UtilsException.class, () -> IdUtils.initialize(1L, 3L, null));
    }

    @Test
    void initializeWithOnlyDatacenterDoesNotBlockStartup() throws Throwable {
        // 场景：只配了 datacenter-id，且 worker-id 无系统属性/环境变量/主机名后缀可解析
        // 期望：不阻断启动——失败推迟到首次生成 ID（与启动监听器的日志承诺一致）
        withIsolatedIdState(() -> assertDoesNotThrow(() -> IdUtils.initialize(null, 7L, null)));
    }

    @Test
    void unresolvableWorkerIdStillFailsOnFirstIdGeneration() throws Throwable {
        // 同一前提下首次生成 ID 必须响亮失败（懒初始化语义不变，不得静默用 0 号机器）
        assumeTrue(System.getenv("YOMI_ID_WORKER_ID") == null
                        && System.getenv("YOMI_ID_ALLOW_HOST_SUFFIX") == null,
                "运行环境自带机器号来源，本断言不适用");
        withIsolatedIdState(() -> assertThrows(UtilsException.class, IdUtils::nextId));
    }

    @Test
    void explicitHostSuffixFlagWinsOverSystemPropertyAndLeavesNoProcessState() throws Throwable {
        // 显式传 false：即使进程级系统属性为 true 也不得回退主机名后缀推导（无单向闩锁）
        withIsolatedIdState(() -> {
            System.setProperty("yomi.id.allow-host-suffix", "true");
            IdUtils.initialize(null, null, false);
            assertNull(readInstance(), "显式关闭主机名后缀时不得初始化");

            // 框架不写进程级系统属性：无论 true 还是 false，调用后系统属性保持原样
            IdUtils.initialize(null, null, true);
            assertEquals("true", System.getProperty("yomi.id.allow-host-suffix"),
                    "框架不得覆写/新增进程级系统属性");
        });
    }

    /** 在"重置雪花单例 + 清空机器号相关系统属性"的前提下执行断言，结束后恢复原状态 */
    private static void withIsolatedIdState(Executable assertion) throws Throwable {
        Field instanceField = IdUtils.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object savedInstance = instanceField.get(null);
        String savedWorkerId = System.getProperty("yomi.id.worker-id");
        String savedAllowHostSuffix = System.getProperty("yomi.id.allow-host-suffix");
        try {
            instanceField.set(null, null);
            System.clearProperty("yomi.id.worker-id");
            System.clearProperty("yomi.id.allow-host-suffix");
            assertion.execute();
        } finally {
            instanceField.set(null, savedInstance);
            restoreProperty("yomi.id.worker-id", savedWorkerId);
            restoreProperty("yomi.id.allow-host-suffix", savedAllowHostSuffix);
        }
    }

    /** 读取当前雪花单例（未初始化返回 null） */
    private static Object readInstance() throws Exception {
        Field instanceField = IdUtils.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        return instanceField.get(null);
    }

    /** 恢复系统属性原值 */
    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

}
