package com.yomi.boot.java.core.boot.listener;

import com.yomi.boot.java.base.exception.UtilsException;
import com.yomi.boot.java.base.utils.IdUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机器号监听器：{@code yomi.id.*} 绑定值显式传入 IdUtils；
 * worker-id 缺失时启动失败，校验发生在首次生成 ID 之前
 */
class IdWorkerEnvironmentListenerTest {

    /** 雪花单例是 JVM 级状态，逐个用例重置，保证各用例的初始化参数独立 */
    @BeforeEach
    void resetSnowflake() throws Exception {
        Field instanceField = IdUtils.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Test
    void initializesFromBindingValues() {
        listener().onApplicationEvent(event(new MockEnvironment()
                .withProperty("yomi.id.worker-id", "5")
                .withProperty("yomi.id.datacenter-id", "6")));

        // 机器号按绑定值初始化（位布局: 时间戳 | datacenterId | workerId | sequence）
        long id = IdUtils.nextId();
        assertEquals(5L, (id >> 12) & 0x1F);
        assertEquals(6L, (id >> 17) & 0x1F);
    }

    @Test
    void datacenterIdDefaultsToZero() {
        listener().onApplicationEvent(event(new MockEnvironment().withProperty("yomi.id.worker-id", "5")));

        assertEquals(0L, (IdUtils.nextId() >> 17) & 0x1F);
    }

    @Test
    void missingWorkerIdFailsStartup() {
        UtilsException e = assertThrows(UtilsException.class,
                () -> listener().onApplicationEvent(event(new MockEnvironment())));
        assertTrue(e.getMessage().contains("yomi.id.worker-id"), "启动失败信息须指向配置项");
    }

    private static IdWorkerEnvironmentListener listener() {
        return new IdWorkerEnvironmentListener();
    }

    private static ApplicationEnvironmentPreparedEvent event(MockEnvironment environment) {
        return new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment);
    }

}
