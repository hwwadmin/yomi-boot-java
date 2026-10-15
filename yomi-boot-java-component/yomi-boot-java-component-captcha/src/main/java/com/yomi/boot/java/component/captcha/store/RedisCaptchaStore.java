package com.yomi.boot.java.component.captcha.store;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 存储（多实例）
 * <p>
 * 键 {@code prefix:captchaId} → 答案，TTL 为挑战有效期；校验用 GETDEL 一次取值，
 * 天然防止并发重放。
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class RedisCaptchaStore implements CaptchaStore {

    /** 字符串 KV 操作 */
    private final StringRedisTemplate redisTemplate;

    /** 挑战键前缀与拼装规则 */
    private final CaptchaKeys keys;

    /** SET 时写入的过期时间 */
    private final Duration ttl;

    /** SET 挑战键并附带 TTL */
    @Override
    public void save(String captchaId, String answer) {
        redisTemplate.opsForValue().set(keys.challengeKey(captchaId), answer, ttl);
    }

    /** GETDEL 一次性取答案，防并发重放 */
    @Override
    public String remove(String captchaId) {
        return redisTemplate.opsForValue().getAndDelete(keys.challengeKey(captchaId));
    }

    /** 存储实现标识 {@code redis} */
    @Override
    public String name() {
        return "redis";
    }

}
