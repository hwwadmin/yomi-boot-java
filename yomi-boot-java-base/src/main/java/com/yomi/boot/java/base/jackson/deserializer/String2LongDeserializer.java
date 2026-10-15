package com.yomi.boot.java.base.jackson.deserializer;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * String/Number 转 Long 反序列化器
 * <p>
 * 与 Long2StringSerializer 配合使用，确保前端以字符串形式传递大数 ID 时
 * 后端可正确反序列化为 Long，避免 JavaScript Number 精度丢失问题。
 *
 * @author zwei
 */
public class String2LongDeserializer extends ValueDeserializer<Long> {

    /** 从 JSON 字符串或数字解析为 Long */
    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctx) {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            String text = p.getString();
            if (StringUtils.isBlank(text)) {
                return null;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw InvalidFormatException.from(p, "Long 格式错误，期望整数", text, Long.class);
            }
        }
        return p.getLongValue();
    }
}
