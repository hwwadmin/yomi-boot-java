package com.yomi.boot.java.component.roundrobin.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundRobinBackoffTest {

    @Test
    void backoffIncreasesAndCaps() {
        assertEquals(Duration.ofSeconds(1), RoundRobinBackoff.duration(1));
        assertEquals(Duration.ofSeconds(32), RoundRobinBackoff.duration(6));
        assertEquals(Duration.ofSeconds(60), RoundRobinBackoff.duration(7));
        assertEquals(Duration.ofSeconds(60), RoundRobinBackoff.duration(20));
    }

}
