package com.yomi.boot.java.base.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XSS工具：检测与清洗共用同一白名单（通过检测的内容清洗后保持原样）
 */
class XssUtilsTest {

    @Test
    void detectsScriptTag() {
        assertTrue(XssUtils.isXssAttack("<script>alert(1)</script>"));
    }

    @Test
    void detectsEventAttributes() {
        assertTrue(XssUtils.isXssAttack("<img src=\"x\" onerror=\"alert(1)\">"));
        assertTrue(XssUtils.isXssAttack("<div onmouseover=\"alert(1)\">x</div>"));
    }

    @Test
    void detectsDangerousProtocols() {
        assertTrue(XssUtils.isXssAttack("<a href=\"javascript:alert(1)\">x</a>"));
        // relaxed 白名单下 img 仅允许 http/https，data: 协议判为攻击
        assertTrue(XssUtils.isXssAttack("<img src=\"data:image/png;base64,AAAA\">"));
    }

    @Test
    void allowsPlainContent() {
        assertFalse(XssUtils.isXssAttack("plain text & 100 < 200"));
        assertFalse(XssUtils.isXssAttack("<b>bold</b>"));
        assertFalse(XssUtils.isXssAttack("<div><img src=\"a.png\"></div>"));
    }

    @Test
    void allowsHttpsLinkWithoutForcedRel() {
        // 锁定 removeEnforcedAttribute("a","rel")：正常超链接不因 rel=nofollow 附加而误报
        assertFalse(XssUtils.isXssAttack("<a href=\"https://example.com\">link</a>"));
    }

    @Test
    void blankIsNotAttack() {
        assertFalse(XssUtils.isXssAttack(null));
        assertFalse(XssUtils.isXssAttack(" "));
    }

    @Test
    void cleanRemovesScriptAndKeepsText() {
        assertEquals("hello", XssUtils.cleanXss("<script>alert(1)</script>hello"));
    }

    @Test
    void cleanPreservesWhitelistedStructure() {
        // 锁定统一白名单：检测放行的 img/div 在清洗后保留（basic() 会剥掉 img）
        String out = XssUtils.cleanXss("<div><img src=\"a.png\"></div>");
        assertTrue(out.contains("<div"));
        assertTrue(out.contains("<img"));
        assertFalse(XssUtils.isXssAttack(out));
    }

    @Test
    void cleanStripsEventAttributes() {
        String out = XssUtils.cleanXss("<img src=\"x\" onerror=\"alert(1)\">");
        assertFalse(out.contains("onerror"));
        assertTrue(out.contains("src=\"x\""));
    }

    @Test
    void cleanBlankPassthrough() {
        assertEquals(null, XssUtils.cleanXss(null));
        assertEquals("", XssUtils.cleanXss(""));
    }

}
