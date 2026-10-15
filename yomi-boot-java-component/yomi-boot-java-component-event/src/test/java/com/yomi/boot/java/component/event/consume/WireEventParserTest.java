package com.yomi.boot.java.component.event.consume;

import com.yomi.boot.java.component.event.model.WireEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireEventParserTest {

    @Test
    void parsesValidEvent() {
        String json = "{\"eventId\":\"e1\",\"topic\":\"t1\",\"payload\":\"{}\"}";
        WireEvent evt = WireEventParser.parse(json, true).orElseThrow();
        assertEquals("e1", evt.getEventId());
        assertEquals("t1", evt.getTopic());
    }

    @Test
    void rejectsMissingEventIdWhenRequired() {
        assertTrue(WireEventParser.parse("{\"topic\":\"t\"}", true).isEmpty());
    }

    /**
     * 语法非法的消息必须按"丢弃"处理而不是抛异常：一旦抛出，毒消息会逃到监听器被反复重投，
     * 调用方的 DROP 分支反而永远不可达
     */
    @Test
    void rejectsMalformedJsonInsteadOfThrowing() {
        assertTrue(WireEventParser.parse("{invalid", true).isEmpty());
        assertTrue(WireEventParser.parse("{\"topic\":", true).isEmpty());
        assertTrue(WireEventParser.parse("not-json-at-all", true).isEmpty());
        assertTrue(WireEventParser.parse("{invalid", false).isEmpty());
        assertTrue(WireEventParser.parse("{invalid".getBytes(StandardCharsets.UTF_8), true).isEmpty());
        assertTrue(WireEventParser.parse("{invalid".getBytes(StandardCharsets.UTF_8), false).isEmpty());
    }

    @Test
    void rejectsBlankOrNullBody() {
        assertTrue(WireEventParser.parse("", true).isEmpty());
        assertTrue(WireEventParser.parse("   ", true).isEmpty());
        assertTrue(WireEventParser.parse("null", true).isEmpty());
        assertTrue(WireEventParser.parse((String) null, true).isEmpty());
        assertTrue(WireEventParser.parse(new byte[0], true).isEmpty());
        assertTrue(WireEventParser.parse((byte[]) null, true).isEmpty());
    }

}
