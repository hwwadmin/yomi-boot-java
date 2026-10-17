package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.event.relay.OutboxRelayCoordinator;
import com.yomi.boot.java.component.event.retention.EventRetentionCleaner;
import com.yomi.boot.java.component.event.support.EventKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件后台任务命名约定：模块前缀 event + 用途驼峰 + {@code _服务名} 后缀
 *
 * @author zwei
 */
class EventTaskNamingTest {

    @Test
    void taskNames_followUnifiedConvention() {
        EventKeys keys = new EventKeys("demo");
        List<String> prefixes = List.of(
                OutboxRelayCoordinator.TASK_FETCH,
                OutboxRelayCoordinator.TASK_FETCH_TIMEOUT,
                OutboxRelayCoordinator.TASK_DLQ,
                EventRetentionCleaner.TASK_NAME_OUTBOX,
                EventRetentionCleaner.TASK_NAME_INBOX);
        for (String prefix : prefixes) {
            assertTrue(prefix.startsWith("event"), prefix + " 应以模块前缀 event 开头");
            assertTrue(prefix.indexOf('_') < 0, prefix + " 前缀不自带分隔符（服务名后缀注册时拼接）");
            assertEquals(prefix + "_demo", keys.roundRobinName(prefix));
        }
        assertEquals("eventOutboxFetch", OutboxRelayCoordinator.TASK_FETCH);
        assertEquals("eventOutboxFetchTimeout", OutboxRelayCoordinator.TASK_FETCH_TIMEOUT);
        assertEquals("eventDlqRetry", OutboxRelayCoordinator.TASK_DLQ);
    }

}
