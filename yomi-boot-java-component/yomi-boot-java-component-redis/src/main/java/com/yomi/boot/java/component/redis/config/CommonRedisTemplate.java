package com.yomi.boot.java.component.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.yomi.boot.java.base.utils.JsonUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通用RedisTemplate
 * <p>value 序列化器以 {@code Object} 为目标且不写类型信息：直接 {@code opsForValue().get} /
 * {@code opsForHash().get} 只承诺 String/Map/List/数字层级（POJO 会读回 {@code LinkedHashMap}）。
 * 需要 POJO 请按类型读：{@link #get(String, Class)}，或使用
 * {@code StringRedisSupport#get(String, Class)}、{@code HashRedisSupport#get(String, String, Class)}</p>
 *
 * @author zwei
 */
public class CommonRedisTemplate extends RedisTemplate<String, Object> {

    public CommonRedisTemplate() {
        JacksonJsonRedisSerializer<Object> jacksonJsonRedisSerializer =
                new JacksonJsonRedisSerializer<>(redisJacksonConfig(), Object.class);
        // key
        setKeySerializer(RedisSerializer.string());
        setHashKeySerializer(RedisSerializer.string());
        // value
        setValueSerializer(jacksonJsonRedisSerializer);
        setHashValueSerializer(jacksonJsonRedisSerializer);
    }

    public CommonRedisTemplate(RedisConnectionFactory connectionFactory) {
        this();
        setConnectionFactory(connectionFactory);
        afterPropertiesSet();
    }

    /**
     * 按键读取并转换为指定类型（对已反序列化的结构再做一次类型转换）；键不存在返回 null
     *
     * @param key  Redis 键
     * @param type 目标类型（POJO / 泛型容器均可）
     */
    public <T> T get(final String key, final Class<T> type) {
        Object value = opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return JsonUtils.parse(JsonUtils.toJson(value), type);
    }

    /**
     * json序列化配置
     */
    private static JsonMapper redisJacksonConfig() {
        return JsonUtils.createObjectMapper()
                .rebuild()
                .changeDefaultVisibility(vc ->
                        vc.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .build();
    }

}
