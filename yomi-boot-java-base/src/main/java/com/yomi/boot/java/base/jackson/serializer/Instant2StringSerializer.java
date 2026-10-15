package com.yomi.boot.java.base.jackson.serializer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.time.Instant;

/**
 * Instant 时间转毫秒时间戳字符串 JSON 序列化器，防止前端精度丢失，
 * 与 {@link com.yomi.boot.java.base.jackson.deserializer.String2InstantDeserializer} 配套使用
 *
 * @author zwei
 */
public class Instant2StringSerializer extends ValueSerializer<Instant> {

    /** 将 Instant 写成毫秒时间戳字符串 */
    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext serializers) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(String.valueOf(value.toEpochMilli()));
    }

}
