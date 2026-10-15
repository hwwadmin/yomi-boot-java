package com.yomi.boot.java.base.jackson.deserializer;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.time.Instant;

/**
 * 字符串转 Instant 时间 JSON 反序列化器
 *
 * @author zwei
 */
public class String2InstantDeserializer extends ValueDeserializer<Instant> {

    /** 从毫秒时间戳字符串解析为 Instant */
    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getString();
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            long l = Long.parseLong(text.trim());
            return Instant.ofEpochMilli(l);
        } catch (NumberFormatException e) {
            throw InvalidFormatException.from(p, "Instant 格式错误，期望毫秒时间戳", text, Instant.class);
        }
    }

}
