package com.yomi.boot.java.base.jackson.serializer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Long 转 String JSON 序列化器，防止前端精度丢失
 *
 * @author zwei
 */
public class Long2StringSerializer extends ValueSerializer<Long> {

    /** 将 Long 写成 JSON 字符串 */
    @Override
    public void serialize(Long value, JsonGenerator gen, SerializationContext serializers) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(value.toString());
    }

}
