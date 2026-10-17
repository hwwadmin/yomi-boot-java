package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.DateFormat;
import com.yomi.boot.java.base.exception.UtilsException;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

/**
 * JSON(jackson)工具类
 *
 * @author zwei
 */
public abstract class JsonUtils {

    /** 默认紧凑输出的 JsonMapper */
    private static final JsonMapper objectMapper = createObjectMapper();
    /** Map<String, Object> 目标类型：键为 JSON 字段名，值按 Object 解析 */
    private static final JavaType mapOfType = objectMapper.getTypeFactory()
            .constructMapType(Map.class, String.class, Object.class);
    /** List<Map<String, Object>> 目标类型：元素必须是 JSON 对象，否则解析当场失败 */
    private static final JavaType listOfMapType = objectMapper.getTypeFactory()
            .constructCollectionType(List.class, mapOfType);

    private JsonUtils() {
    }

    /**
     * 对象序列化为 JSON 字符串（紧凑格式，无缩进换行）
     */
    public static String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JacksonException e) {
            throw UtilsException.exception(e, "json 解析失败");
        }
    }

    /**
     * JSON字符串解析为对象
     */
    public static <T> T parse(String json, TypeReference<T> type) {
        return parse(json, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * JSON字符串解析为对象
     */
    public static <T> T parse(String json, Class<T> type) {
        return parse(json, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * JSON字符串解析为对象
     */
    public static <T> T parse(String json, Type type) {
        return parse(json, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * JSON字符串解析为对象
     */
    public static <T> T parse(String json, JavaType type) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw UtilsException.exception(e, "Json字符串解析错误，解析" + type.getTypeName());
        }
    }

    /**
     * Json字符串解析为List<Map<String, Object>>
     * <p>
     * 元素须为 JSON 对象；非对象元素解析失败并抛 {@link UtilsException}
     */
    public static List<Map<String, Object>> jsonStr2List(String jsonString) {
        if (StringUtils.isBlank(jsonString)) {
            return Lists.of();
        }
        try {
            List<Map<String, Object>> result = objectMapper.readValue(jsonString, listOfMapType);
            return result == null ? Lists.of() : result;
        } catch (Exception e) {
            throw UtilsException.exception(e, "Json字符串解析为List<Map<String, Object>>错误");
        }
    }

    /**
     * Json字符串解析为List<T>
     */
    public static <T> List<T> jsonStr2List(String jsonString, Class<T> clazz) {
        if (StringUtils.isBlank(jsonString)) {
            return Lists.of();
        }
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructParametricType(List.class, clazz);
            List<T> result = objectMapper.readValue(jsonString, javaType);
            return result == null ? Lists.of() : result;
        } catch (Exception e) {
            throw UtilsException.exception(e, "Json字符串解析为List<T>错误");
        }
    }

    /**
     * Json字符串解析为Map
     * <p>
     * 结构必须是 JSON 对象，嵌套对象仍为 Map、数组为 List，与声明类型一致
     */
    public static Map<String, Object> jsonStr2Map(String jsonString) {
        if (StringUtils.isBlank(jsonString)) {
            return Maps.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(jsonString, mapOfType);
            return result == null ? Maps.of() : result;
        } catch (Exception e) {
            throw UtilsException.exception(e, "Json字符串解析为Map错误");
        }
    }

    /**
     * 获取 JsonMapper 对象（紧凑格式，不带缩进）
     */
    public static JsonMapper createObjectMapper() {
        return createObjectMapper(false);
    }

    /**
     * 获取 JsonMapper 对象
     *
     * @param prettier 是否开启 pretty-print（缩进换行）
     */
    public static JsonMapper createObjectMapper(boolean prettier) {
        JsonMapper.Builder builder = JsonMapper.builderWithJackson2Defaults();
        // 格式化 JSON
        if (prettier) {
            builder.enable(SerializationFeature.INDENT_OUTPUT);
        }
        // 日期序列化开关（被下方 defaultDateFormat 覆盖，实际输出为字符串）
        builder.configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        builder.configure(DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS, false);
        // 时区设置
        builder.defaultTimeZone(TimeZoneUtils.timeZone());
        // 日期格式：Date/Calendar 序列化为 "yyyy-MM-dd HH:mm:ss"（UTC），反序列化兼容该格式与毫秒数字时间戳
        builder.defaultDateFormat(new SimpleDateFormat(DateFormat.YYYYVMMVDDHHSS.getValue()));
        // 序列化/反序列化枚举使用 toString()，不使用 ordinal()
        builder.enable(EnumFeature.WRITE_ENUMS_USING_TO_STRING);
        builder.enable(EnumFeature.READ_ENUMS_USING_TO_STRING);
        builder.disable(EnumFeature.WRITE_ENUMS_USING_INDEX);
        // 空 bean 正常序列化
        builder.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        // 反序列化忽略未知属性
        builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 单引号处理
        builder.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES);
        return builder.build();
    }

}
