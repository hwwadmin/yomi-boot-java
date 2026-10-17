package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TimeZoneUtils} 解析规则
 */
class TimeZoneUtilsTest {

    @Test
    void resolveZoneIdDefaultsToUtc() {
        assertEquals(ZoneId.of("UTC"), TimeZoneUtils.resolveZoneId(null, null));
        assertEquals(ZoneId.of("UTC"), TimeZoneUtils.resolveZoneId("  ", ""));
    }

    @Test
    void resolveZoneIdPrefersSystemPropertyOverEnv() {
        assertEquals(ZoneId.of("Asia/Shanghai"),
                TimeZoneUtils.resolveZoneId("Asia/Shanghai", "Europe/London"));
    }

    @Test
    void resolveZoneIdUsesEnvWhenPropertyBlank() {
        assertEquals(ZoneId.of("Europe/London"),
                TimeZoneUtils.resolveZoneId(null, "Europe/London"));
    }

    @Test
    void resolveZoneIdTrimsValue() {
        assertEquals(ZoneId.of("GMT+8"), TimeZoneUtils.resolveZoneId("  GMT+8  ", null));
    }

    /** 未知 ID（ZoneRulesException）与格式非法（DateTimeException）都抛出带键值的 UtilsException */
    @Test
    void resolveZoneIdRejectsInvalidIdWithKeyAndValue() {
        UtilsException propertyEx = assertThrows(UtilsException.class,
                () -> TimeZoneUtils.resolveZoneId("Asia/Shangai", null));
        assertEquals("illegal time zone config: yomi.time-zone=Asia/Shangai", propertyEx.getMessage());

        UtilsException envEx = assertThrows(UtilsException.class,
                () -> TimeZoneUtils.resolveZoneId(null, "  Not/A/Zone  "));
        assertEquals("illegal time zone config: YOMI_TIME_ZONE=Not/A/Zone", envEx.getMessage());

        UtilsException formatEx = assertThrows(UtilsException.class,
                () -> TimeZoneUtils.resolveZoneId("@@@", null));
        assertEquals("illegal time zone config: yomi.time-zone=@@@", formatEx.getMessage());

        assertInstanceOf(DateTimeException.class, propertyEx.getCause());
        assertInstanceOf(DateTimeException.class, formatEx.getCause());
    }

    @Test
    void zoneIdMatchesResolvedAtClassLoad() {
        assertEquals(TimeZoneUtils.resolveZoneId(
                System.getProperty(TimeZoneUtils.PROP_TIME_ZONE),
                System.getenv(TimeZoneUtils.ENV_TIME_ZONE)), TimeZoneUtils.zoneId());
    }

}
