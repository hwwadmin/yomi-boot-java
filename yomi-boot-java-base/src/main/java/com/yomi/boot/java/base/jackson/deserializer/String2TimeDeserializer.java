package com.yomi.boot.java.base.jackson.deserializer;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.sql.Time;

/**
 * 字符串转 Time 时间 JSON 反序列化器
 *
 * @author zwei
 */
public class String2TimeDeserializer extends ValueDeserializer<Time> {

    /** 从 HH:mm 或 HH:mm:ss 字符串解析为 {@link Time} */
    @Override
    public Time deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getString();
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            text = text.trim();
            int firstColon = text.indexOf(':');
            int secondColon = text.indexOf(':', firstColon + 1);
            if (firstColon > 0 && secondColon < 0) {
                text = text + ":00";
            }
            return Time.valueOf(text);
        } catch (Exception e) {
            throw InvalidFormatException.from(p, "Time 格式错误，期望 HH:mm 或 HH:mm:ss", text, Time.class);
        }
    }

}
