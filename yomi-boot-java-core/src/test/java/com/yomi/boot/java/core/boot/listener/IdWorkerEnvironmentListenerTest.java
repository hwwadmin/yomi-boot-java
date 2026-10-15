package com.yomi.boot.java.core.boot.listener;

import com.yomi.boot.java.base.utils.IdUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 机器号监听器：{@code yomi.id.*} 绑定值显式传入 IdUtils，
 * 不写进程级系统属性（避免同 JVM 内后续上下文被前一个上下文的状态污染）
 */
class IdWorkerEnvironmentListenerTest {

    @Test
    void passesBindingValuesExplicitlyAndWritesNoProcessProperty() {
        System.clearProperty("yomi.id.allow-host-suffix");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("yomi.id.worker-id", "5")
                .withProperty("yomi.id.datacenter-id", "6")
                .withProperty("yomi.id.allow-host-suffix", "true");

        new IdWorkerEnvironmentListener().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));

        // 机器号按绑定值初始化（位布局: 时间戳 | datacenterId | workerId | sequence）
        long id = IdUtils.nextId();
        assertEquals(5L, (id >> 12) & 0x1F);
        assertEquals(6L, (id >> 17) & 0x1F);
        // 无单向闩锁：无论配置 true 还是 false，都不得产生进程级系统属性
        assertNull(System.getProperty("yomi.id.allow-host-suffix"), "监听器不得写进程级系统属性");
    }

}
