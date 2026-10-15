package com.yomi.boot.java.core.boot.config;

import com.yomi.boot.java.base.jackson.deserializer.String2InstantDeserializer;
import com.yomi.boot.java.base.jackson.deserializer.String2LongDeserializer;
import com.yomi.boot.java.base.jackson.deserializer.String2TimeDeserializer;
import com.yomi.boot.java.base.jackson.serializer.Instant2StringSerializer;
import com.yomi.boot.java.base.jackson.serializer.Long2StringSerializer;
import com.yomi.boot.java.base.utils.JsonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.sql.Time;
import java.time.Instant;

@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    public JacksonJsonHttpMessageConverter jackson2HttpMessageConverter() {
        JsonMapper baseMapper = JsonUtils.createObjectMapper(false);
        SimpleModule module = new SimpleModule();

        // Serializer：Long 与基本类型 long 都要转字符串，否则 DTO 用 long 时仍输出数字、前端丢精度
        module.addSerializer(Long.class, new Long2StringSerializer());
        module.addSerializer(Long.TYPE, new Long2StringSerializer());
        module.addSerializer(Instant.class, new Instant2StringSerializer());

        // Deserializer：与序列化对称，long 字段同样接受字符串与数字
        module.addDeserializer(Long.class, new String2LongDeserializer());
        module.addDeserializer(Long.TYPE, new String2LongDeserializer());
        module.addDeserializer(Time.class, new String2TimeDeserializer());
        module.addDeserializer(Instant.class, new String2InstantDeserializer());

        JsonMapper jsonMapper = baseMapper.rebuild()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .addModule(module)
                .build();
        return new JacksonJsonHttpMessageConverter(jsonMapper);
    }

}
