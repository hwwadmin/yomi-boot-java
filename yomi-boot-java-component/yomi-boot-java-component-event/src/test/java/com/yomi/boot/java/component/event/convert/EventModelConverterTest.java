package com.yomi.boot.java.component.event.convert;

import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.EventDlqModel;
import com.yomi.boot.java.component.event.model.EventOutboxModel;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventModelConverter} 字段映射完整性
 *
 * @author zwei
 */
class EventModelConverterTest {

    private static final Instant CREATE_TIME = Instant.parse("2026-10-13T01:02:03Z");

    private static final Instant FETCH_TIME = Instant.parse("2026-10-13T02:03:04Z");

    private static final Instant SENT_TIME = Instant.parse("2026-10-13T03:04:05Z");

    private static final Instant RETRY_LAST_TIME = Instant.parse("2026-10-13T04:05:06Z");

    private static final Instant RETRY_AFTER_TIME = Instant.parse("2026-10-13T05:06:07Z");

    @Test
    void toOutboxModelCopiesAllFields() throws Exception {
        EventOutbox entity = new EventOutbox();
        entity.setId(11L);
        entity.setCreateTime(CREATE_TIME);
        entity.setCreateBy(12L);
        entity.setUpdateTime(SENT_TIME);
        entity.setUpdateBy(13L);
        entity.setServiceName("svc-demo");
        entity.setEventId("evt-1");
        entity.setTopic("topic-demo");
        entity.setPayload("{\"k\":1}");
        entity.setStatus(OutboxStatus.SENT_FAIL);
        entity.setFetchTime(FETCH_TIME);
        entity.setSentTime(SENT_TIME);

        EventOutboxModel model = EventModelConverter.toOutboxModel(entity);

        assertAllCopied(entity, model);
    }

    @Test
    void toDlqModelCopiesAllFieldsAndLeavesEventIdNull() throws Exception {
        EventOutboxDlq entity = new EventOutboxDlq();
        entity.setId(21L);
        entity.setCreateTime(CREATE_TIME);
        entity.setCreateBy(22L);
        entity.setUpdateTime(RETRY_LAST_TIME);
        entity.setUpdateBy(23L);
        entity.setServiceName("svc-demo");
        entity.setEventOutboxId(11L);
        entity.setErrorMsg("发送失败");
        entity.setRetryCount(3);
        entity.setRetryLastTime(RETRY_LAST_TIME);
        entity.setRetryAfterTime(RETRY_AFTER_TIME);

        EventDlqModel model = EventModelConverter.toDlqModel(entity);

        assertAllCopied(entity, model);
        assertNull(model.getEventId(), "关联 eventId 应由服务层批量补全");
    }

    /** 逐字段比对模型与实体同名字段，实体新增字段漏映射时失败 */
    private static void assertAllCopied(Object entity, Object model) throws Exception {
        int copied = 0;
        for (Field field : model.getClass().getDeclaredFields()) {
            Field source = findField(entity.getClass(), field.getName());
            if (source == null) {
                continue;
            }
            field.setAccessible(true);
            source.setAccessible(true);
            assertEquals(source.get(entity), field.get(model), "字段未复制: " + field.getName());
            copied++;
        }
        assertTrue(copied > 0, "未比对到任何字段，反射匹配失效");
    }

    /** 沿类层级按名字查找字段（实体字段含 BaseEntity 继承部分） */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 当前类无该字段，继续向父类查找
            }
        }
        return null;
    }

}
