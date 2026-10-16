package com.yomi.boot.java.component.idempotency.support;

import com.yomi.boot.java.base.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * 幂等结果 JSON 编解码：DB 记录与 Redis 缓存共用一套序列化配置（日期不写时间戳）
 *
 * @author zwei
 */
public final class IdempotencyResultJson {

    /** 结果 JSON 编解码用的 JsonMapper */
    private static final JsonMapper MAPPER = JsonUtils.createObjectMapper().rebuild()
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    private IdempotencyResultJson() {
    }

    /** 序列化业务返回值；null 与 JSON 字面量 {@code "null"} 都返回 null */
    public static String marshal(Object value) {
        if (value == null) {
            return null;
        }
        String json = MAPPER.writeValueAsString(value);
        return "null".equals(json) ? null : json;
    }

    /** 反序列化业务返回值；空白返回 null */
    public static Object unmarshal(String payload, Type type) {
        if (StringUtils.isBlank(payload)) {
            return null;
        }
        return MAPPER.readValue(payload, MAPPER.constructType(type));
    }

}
