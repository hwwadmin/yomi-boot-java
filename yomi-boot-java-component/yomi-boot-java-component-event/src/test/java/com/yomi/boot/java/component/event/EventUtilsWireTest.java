package com.yomi.boot.java.component.event;

import com.yomi.boot.java.component.event.model.WireEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventUtilsWireTest {

    @Test
    void wireAndParseRoundTrip() {
        WireEvent event = EventUtils.wire("e1", "demo.topic", "{\"k\":1}");
        String json = EventUtils.toJson(event);
        WireEvent parsed = EventUtils.parse(json).orElseThrow();
        assertEquals("e1", parsed.getEventId());
        assertEquals("demo.topic", parsed.getTopic());
    }

    @Test
    void newEventIdIsNonBlank() {
        assertTrue(EventUtils.newEventId().length() > 10);
    }

}
