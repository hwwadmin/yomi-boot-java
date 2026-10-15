package com.yomi.boot.java.component.httplog.convert;

import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpLogModelConverter} 字段映射完整性
 *
 * @author zwei
 */
class HttpLogModelConverterTest {

    private static final Instant CREATE_TIME = Instant.parse("2026-10-13T01:02:03Z");

    private static final Instant REQUEST_TIME = Instant.parse("2026-10-13T01:02:04Z");

    @Test
    void toHttpLogModelCopiesAllFields() throws Exception {
        HttpLogEntity entity = new HttpLogEntity();
        entity.setId(11L);
        entity.setCreateTime(CREATE_TIME);
        entity.setCreateBy(12L);
        entity.setUpdateTime(REQUEST_TIME);
        entity.setUpdateBy(13L);
        entity.setServiceName("svc-demo");
        entity.setTraceId("trace-1");
        entity.setRequestTime(REQUEST_TIME);
        entity.setDurationMs(37);
        entity.setMethod("POST");
        entity.setUri("/api/demo");
        entity.setQueryString("id=1");
        entity.setStatus(200);
        entity.setClientIp("127.0.0.1");
        entity.setUserId(14L);
        entity.setRequestHeaders("{\"h\":\"v\"}");
        entity.setRequestBody("{\"k\":1}");
        entity.setRequestBodyTruncated(true);
        entity.setResponseHeaders("{\"r\":\"v\"}");
        entity.setResponseBody("{\"ok\":true}");
        entity.setResponseBodyTruncated(false);
        entity.setErrorMsg("错误信息");
        entity.setAsyncStarted(true);

        HttpLogModel model = HttpLogModelConverter.toHttpLogModel(entity);

        assertAllCopied(entity, model);
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
