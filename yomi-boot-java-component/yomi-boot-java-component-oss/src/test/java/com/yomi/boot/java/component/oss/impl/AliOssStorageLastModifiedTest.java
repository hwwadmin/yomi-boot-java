package com.yomi.boot.java.component.oss.impl;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 阿里云 OSS {@code Last-Modified} 的 RFC 1123 与 ISO-8601 解析 */
class AliOssStorageLastModifiedTest {

    @Test
    void parsesRfc1123HttpDate() {
        Instant expected = ZonedDateTime.of(2025, 9, 5, 3, 39, 38, 0, ZoneOffset.UTC).toInstant();

        assertEquals(expected, AliOssStorage.parseLastModified("Fri, 05 Sep 2025 03:39:38 GMT"));
        assertEquals(expected, AliOssStorage.parseLastModified("  Fri, 05 Sep 2025 03:39:38 GMT  "),
                "带空白的头值也应可解析");
    }

    @Test
    void stillAcceptsIso8601() {
        Instant expected = Instant.parse("2025-09-05T03:39:38Z");

        assertEquals(expected, AliOssStorage.parseLastModified("2025-09-05T03:39:38Z"));
    }

    @Test
    void returnsNullForBlankOrUnparseable() {
        assertNull(AliOssStorage.parseLastModified(null));
        assertNull(AliOssStorage.parseLastModified("  "));
        assertNull(AliOssStorage.parseLastModified("not-a-date"));
    }

}
