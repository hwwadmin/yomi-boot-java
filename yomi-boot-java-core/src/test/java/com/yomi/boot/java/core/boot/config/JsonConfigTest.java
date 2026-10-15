package com.yomi.boot.java.core.boot.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 层 JSON 契约：Long 与基本类型 long 都序列化为字符串（防前端精度丢失），读写对称。
 */
class JsonConfigTest {

    private final JacksonJsonHttpMessageConverter converter = new JsonConfig().jackson2HttpMessageConverter();

    @Test
    void primitiveAndBoxedLongAreBothSerializedAsString() throws Exception {
        MockHttpOutputMessage output = new MockHttpOutputMessage();
        converter.write(new Ids(1234567890123456789L, 1234567890123456789L), MediaType.APPLICATION_JSON, output);

        String json = output.getBodyAsString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"primitiveId\":\"1234567890123456789\""), json);
        assertTrue(json.contains("\"boxedId\":\"1234567890123456789\""), json);
    }

    @Test
    void primitiveAndBoxedLongAcceptStringOnRead() throws Exception {
        Ids fromString = read("{\"primitiveId\":\"123\",\"boxedId\":\"456\"}");
        assertEquals(123L, fromString.primitiveId());
        assertEquals(456L, fromString.boxedId());

        Ids fromNumber = read("{\"primitiveId\":123,\"boxedId\":456}");
        assertEquals(123L, fromNumber.primitiveId());
        assertEquals(456L, fromNumber.boxedId());
    }

    private Ids read(String json) throws Exception {
        return (Ids) converter.read(Ids.class,
                new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** 出入参 DTO：覆盖基本类型与包装类型 */
    record Ids(long primitiveId, Long boxedId) {
    }

}
