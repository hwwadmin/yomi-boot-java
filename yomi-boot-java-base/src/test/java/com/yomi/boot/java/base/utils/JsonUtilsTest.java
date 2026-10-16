package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON工具：往返、null约定、日期毫秒时间戳、枚举toString、宽松解析
 */
class JsonUtilsTest {

    record Point(int x, int y) {
    }

    enum Mode {
        A, B;

        @Override
        public String toString() {
            return "mode-" + name();
        }
    }

    record ModeBean(Mode mode) {
    }

    /** public字段 + 无参构造，供 map 转 bean 往返用例使用 */
    static class Bean {
        public int x;
        public String name;
    }

    @Test
    void toJsonAndParseRoundTrip() {
        Point p = new Point(1, 2);
        assertEquals("{\"x\":1,\"y\":2}", JsonUtils.toJson(p));
        assertEquals(p, JsonUtils.parse("{\"x\":1,\"y\":2}", Point.class));
    }

    @Test
    void parseBlankReturnsNull() {
        assertNull(JsonUtils.parse(" ", Point.class));
    }

    @Test
    void parseInvalidThrowsUtilsException() {
        assertThrows(UtilsException.class, () -> JsonUtils.parse("{bad", Point.class));
        // 目标类型不匹配同样走异常包装
        assertThrows(UtilsException.class, () -> JsonUtils.parse("\"str\"", Point.class));
    }

    @Test
    void enumSerializedByToString() {
        assertEquals("{\"mode\":\"mode-A\"}", JsonUtils.toJson(new ModeBean(Mode.A)));
        assertEquals(Mode.B, JsonUtils.parse("{\"mode\":\"mode-B\"}", ModeBean.class).mode());
    }

    @Test
    void dateSerializedAsDefaultPatternInUtc() throws Exception {
        // 显式设置 defaultDateFormat 后，Date 序列化走该格式（优先于时间戳特性），时区为 mapper 默认 UTC
        assertEquals("\"2023-11-14 22:13:20\"", JsonUtils.toJson(new Date(1700000000000L)));
        // 反序列化兼容该格式字符串
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals(sdf.parse("2023-11-14 22:13:20"),
                JsonUtils.parse("\"2023-11-14 22:13:20\"", Date.class));
        // 毫秒时间戳须以 JSON 数字形式传入
        assertEquals(1700000000000L, JsonUtils.parse("1700000000000", Date.class).getTime());
    }

    @Test
    void dateParsedFromLegacyStringPattern() throws Exception {
        // mapper 默认时区 UTC，字符串日期按 "yyyy-MM-dd HH:mm:ss" 解析
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals(sdf.parse("2024-01-01 10:30:00"),
                JsonUtils.parse("\"2024-01-01 10:30:00\"", Date.class));
    }

    @Test
    void lenientParsing() {
        // 空 bean 不抛异常（FAIL_ON_EMPTY_BEANS 关闭）
        assertEquals("{}", JsonUtils.toJson(new Object()));
        // 未知属性忽略（FAIL_ON_UNKNOWN_PROPERTIES 关闭）
        assertEquals(1, JsonUtils.parse("{\"x\":1,\"unknown\":9}", Point.class).x());
        // 单引号容忍
        assertEquals(new Point(1, 2), JsonUtils.parse("{'x':1,'y':2}", Point.class));
    }

    @Test
    void nullAndEmptyConventions() {
        assertEquals("null", JsonUtils.toJson(null));
        assertTrue(JsonUtils.jsonStr2List(" ").isEmpty());
        assertTrue(JsonUtils.jsonStr2Map(" ").isEmpty());
        assertTrue(JsonUtils.jsonStr2List(null, Point.class).isEmpty());
    }

    @Test
    void jsonStr2ListVariants() {
        List<Map<String, Object>> raw = JsonUtils.jsonStr2List("[{\"a\":1},{\"b\":2}]");
        assertEquals(2, raw.size());
        assertEquals(1, raw.get(0).get("a"));
        assertEquals(List.of(1, 2, 3), JsonUtils.jsonStr2List("[1,2,3]", Integer.class));
    }

    @Test
    void jsonStr2ListRejectsNonObjectElements() {
        // 元素必须是 JSON 对象：非对象当场抛异常，不再返回裸 List 让调用方遍历时抛延迟 CCE
        assertThrows(UtilsException.class, () -> JsonUtils.jsonStr2List("[1,2,3]"));
        // 元素（含嵌套对象/数组）按声明类型解析，遍历与取值不产生 CCE
        List<Map<String, Object>> list = JsonUtils.jsonStr2List("[{\"a\":{\"b\":2}},{\"c\":[1,2]}]");
        for (Map<String, Object> element : list) {
            assertNotNull(element);
        }
        assertEquals(2, ((Map<?, ?>) list.get(0).get("a")).get("b"));
        assertEquals(List.of(1, 2), list.get(1).get("c"));
    }

    @Test
    void jsonStr2MapVariant() {
        Map<String, Object> map = JsonUtils.jsonStr2Map("{\"a\":1}");
        assertEquals(1, map.get("a"));
        // 嵌套结构类型与声明一致：对象为 Map、数组为 List
        Map<String, Object> nested = JsonUtils.jsonStr2Map("{\"o\":{\"x\":\"y\"},\"arr\":[1,2]}");
        assertEquals("y", ((Map<?, ?>) nested.get("o")).get("x"));
        assertEquals(List.of(1, 2), nested.get("arr"));
    }

    @Test
    void mapToBeanRoundTrip() {
        Map<String, Object> map = Maps.of();
        map.put("x", 3);
        map.put("name", "n");
        // map 转 bean：先序列化为 JSON 再按目标类型解析
        Bean bean = JsonUtils.parse(JsonUtils.toJson(map), Bean.class);
        assertEquals(3, bean.x);
        assertEquals("n", bean.name);

        Map<String, Object> back = JsonUtils.jsonStr2Map(JsonUtils.toJson(bean));
        assertEquals(3, back.get("x"));
        assertEquals("n", back.get("name"));
    }

}
