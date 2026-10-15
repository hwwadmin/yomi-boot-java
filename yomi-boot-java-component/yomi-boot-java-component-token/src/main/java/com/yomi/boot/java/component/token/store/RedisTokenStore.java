package com.yomi.boot.java.component.token.store;

import com.yomi.boot.java.base.exception.UtilsException;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.token.session.TokenSession;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis 的令牌会话存储（{@link TokenStore} 默认实现）
 * <p>
 * 键结构（前缀由 {@link TokenKeys} 规范化）：
 * <ul>
 *     <li>快照键 {@code {prefix:u:userId}:s:{sha256(token)}} → 会话快照 JSON，TTL 由 expireAt 推导；</li>
 *     <li>用户索引键 {@code {prefix:u:userId}:tokens}（SET，成员为散列令牌），TTL 只延长不缩短；</li>
 *     <li>路由键 {@code prefix:rt:{sha256(token)}} → userId，供按 token 校验（Cluster 单键读）。</li>
 * </ul>
 * 索引与快照的跨键原子性由 Lua 保证（同 hash tag）；路由键在 Lua 成功后写入。
 *
 * @author zwei
 */
public class RedisTokenStore implements TokenStore {

    /** 会话快照 JSON 编解码：时间统一为 ISO-8601 字符串，读写同一 Mapper 自洽 */
    private static final JsonMapper MAPPER = JsonUtils.createObjectMapper().rebuild()
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    /**
     * 保存：索引 SADD → 快照 SET(PX) → 索引 TTL 只延长不缩短
     * KEYS[1]=用户索引键 KEYS[2]=快照键 ARGV[1]=散列令牌 ARGV[2]=快照JSON ARGV[3]=TTL毫秒
     */
    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('SET', KEYS[2], ARGV[2], 'PX', tonumber(ARGV[3]))
            local ttl = tonumber(ARGV[3])
            local indexTtl = redis.call('PTTL', KEYS[1])
            if indexTtl < ttl then
                redis.call('PEXPIRE', KEYS[1], ttl)
            end
            return 1
            """, Long.class);

    /**
     * 单点登录替换：删用户全部旧快照 → 写入新快照并重建索引
     * KEYS[1]=用户索引键 KEYS[2]=新快照键
     * ARGV[1]=快照键前缀（{@code {prefix:u:userId}:s:}） ARGV[2]=散列令牌 ARGV[3]=快照JSON ARGV[4]=TTL毫秒
     */
    private static final RedisScript<Long> REPLACE_ALL_FOR_USER_SCRIPT = new DefaultRedisScript<>("""
            local tokens = redis.call('SMEMBERS', KEYS[1])
            local sessionPrefix = ARGV[1]
            for i = 1, #tokens do
                redis.call('DEL', sessionPrefix .. tokens[i])
            end
            redis.call('DEL', KEYS[1])
            redis.call('SADD', KEYS[1], ARGV[2])
            redis.call('SET', KEYS[2], ARGV[3], 'PX', tonumber(ARGV[4]))
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
            return 1
            """, Long.class);

    /**
     * 删除单令牌：DEL 快照并从用户索引 SREM
     * KEYS[1]=快照键 KEYS[2]=用户索引键 ARGV[1]=散列令牌
     */
    private static final RedisScript<Long> DELETE_TOKEN_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    /**
     * 滑动续期：仅当快照仍存在时写回（原子守卫）。
     * KEYS[1]=快照键 KEYS[2]=用户索引键 ARGV[1]=散列令牌 ARGV[2]=快照JSON ARGV[3]=TTL毫秒
     */
    private static final RedisScript<Long> RENEW_IF_PRESENT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('SET', KEYS[1], ARGV[2], 'PX', tonumber(ARGV[3]))
            local ttl = tonumber(ARGV[3])
            local indexTtl = redis.call('PTTL', KEYS[2])
            if indexTtl < ttl then
                redis.call('PEXPIRE', KEYS[2], ttl)
            end
            return 1
            """, Long.class);

    /**
     * 按用户撤销：原子删除全部快照与索引
     * <p>与签发脚本在 Redis 端串行（单线程执行脚本），消除"读索引 → 并发签发 → 删索引"把新令牌
     * 留成"快照在、索引不在"漏网会话的窗口；先执行脚本者撤销全部、后执行者（签发）生效，语义线性可比</p>
     * KEYS[1]=用户索引键 ARGV[1]=快照键前缀（{@code {prefix:u:userId}:s:}）
     */
    private static final RedisScript<Long> DELETE_ALL_FOR_USER_SCRIPT = new DefaultRedisScript<>("""
            local tokens = redis.call('SMEMBERS', KEYS[1])
            local sessionPrefix = ARGV[1]
            for i = 1, #tokens do
                redis.call('DEL', sessionPrefix .. tokens[i])
            end
            redis.call('DEL', KEYS[1])
            return #tokens
            """, Long.class);

    /** 会话快照与索引 */
    private final StringRedisTemplate redisTemplate;

    /** Redis 键名 */
    private final TokenKeys keys;

    public RedisTokenStore(StringRedisTemplate redisTemplate, TokenKeys keys) {
        Assert.nonNull(redisTemplate, "redisTemplate must be not null");
        Assert.nonNull(keys, "keys must be not null");
        this.redisTemplate = redisTemplate;
        this.keys = keys;
    }

    /** 写入令牌与用户索引并设置 TTL */
    @Override
    public void save(String token, TokenSession session) {
        Assert.isTrue(StringUtils.isNotBlank(token), "令牌不能为空");
        Assert.nonNull(session, "令牌会话不能为空");
        Assert.isTrue(session.isValid(), "令牌会话不合法：用户ID与签发时间不能为空");
        Assert.nonNull(session.getExpireAt(), "令牌会话不合法：过期时间不能为空");
        long ttlMillis = ttlMillis(session.getExpireAt());
        Assert.isTrue(ttlMillis > 0, "令牌会话已过期，拒绝保存");

        Long userId = session.getUserId();
        String storageHash = TokenKeys.hashToken(token);
        try {
            redisTemplate.execute(SAVE_SCRIPT,
                    List.of(keys.userTokensKey(userId), keys.sessionKey(userId, token)),
                    storageHash,
                    toJson(session),
                    String.valueOf(ttlMillis));
            saveRoute(storageHash, userId, ttlMillis);
        } catch (RuntimeException e) {
            deleteSessionQuietly(userId, storageHash);
            throw e;
        }
    }

    /** 替换用户全部令牌（单端登录等） */
    @Override
    public void replaceAllForUser(String token, TokenSession session) {
        Assert.isTrue(StringUtils.isNotBlank(token), "令牌不能为空");
        Assert.nonNull(session, "令牌会话不能为空");
        Assert.isTrue(session.isValid(), "令牌会话不合法：用户ID与签发时间不能为空");
        Assert.nonNull(session.getExpireAt(), "令牌会话不合法：过期时间不能为空");
        long ttlMillis = ttlMillis(session.getExpireAt());
        Assert.isTrue(ttlMillis > 0, "令牌会话已过期，拒绝保存");

        Long userId = session.getUserId();
        String storageHash = TokenKeys.hashToken(token);
        Set<String> previousHashes = redisTemplate.opsForSet().members(keys.userTokensKey(userId));

        try {
            redisTemplate.execute(REPLACE_ALL_FOR_USER_SCRIPT,
                    List.of(keys.userTokensKey(userId), keys.sessionKey(userId, token)),
                    keys.sessionKeyPrefix(userId),
                    storageHash,
                    toJson(session),
                    String.valueOf(ttlMillis));
            deleteRoutesQuietly(previousHashes);
            saveRoute(storageHash, userId, ttlMillis);
        } catch (RuntimeException e) {
            deleteSessionQuietly(userId, storageHash);
            throw e;
        }
    }

    /** 读取令牌绑定的用户 id */
    @Override
    public TokenSession load(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        String storageHash = TokenKeys.hashToken(token);
        Long userId = parseRouteUserId(redisTemplate.opsForValue().get(keys.routeKeyByStorageToken(storageHash)));
        if (userId == null) {
            return null;
        }
        return tryFromJson(redisTemplate.opsForValue().get(keys.sessionKeyByStorageToken(userId, storageHash)));
    }

    /** 存在则刷新 TTL */
    @Override
    public boolean renewIfPresent(String token, TokenSession session) {
        Assert.isTrue(StringUtils.isNotBlank(token), "令牌不能为空");
        Assert.nonNull(session, "令牌会话不能为空");
        Assert.isTrue(session.isValid(), "令牌会话不合法：用户ID与签发时间不能为空");
        Assert.nonNull(session.getExpireAt(), "令牌会话不合法：过期时间不能为空");
        long ttlMillis = ttlMillis(session.getExpireAt());
        Assert.isTrue(ttlMillis > 0, "令牌会话已过期，拒绝续期");

        Long userId = session.getUserId();
        String storageHash = TokenKeys.hashToken(token);
        Long result = redisTemplate.execute(RENEW_IF_PRESENT_SCRIPT,
                List.of(keys.sessionKey(userId, token), keys.userTokensKey(userId)),
                storageHash,
                toJson(session),
                String.valueOf(ttlMillis));
        boolean renewed = result != null && result == 1L;
        if (renewed) {
            saveRoute(storageHash, userId, ttlMillis);
        }
        return renewed;
    }

    /** 删除单个令牌及其索引 */
    @Override
    public void delete(String token) {
        if (StringUtils.isBlank(token)) {
            return;
        }
        String storageHash = TokenKeys.hashToken(token);
        Long userId = parseRouteUserId(redisTemplate.opsForValue().get(keys.routeKeyByStorageToken(storageHash)));
        if (userId != null && userId > 0) {
            redisTemplate.execute(DELETE_TOKEN_SCRIPT,
                    List.of(keys.sessionKeyByStorageToken(userId, storageHash), keys.userTokensKey(userId)),
                    storageHash);
        }
        redisTemplate.delete(keys.routeKeyByStorageToken(storageHash));
    }

    /** 删除用户下全部令牌 */
    @Override
    public void deleteByUserId(Long userId) {
        Assert.nonNull(userId, "用户ID不能为空");
        // 成员只用于路由键清理（路由键无 hash tag、Lua 访问不了）；索引与快照的删除必须在同一条脚本内，
        // 否则并发签发会把新令牌留成"快照在、索引不在"的漏网会话（撤销不了、列表也看不到）
        Set<String> membersBeforeRevoke = redisTemplate.opsForSet().members(keys.userTokensKey(userId));
        redisTemplate.execute(DELETE_ALL_FOR_USER_SCRIPT,
                List.of(keys.userTokensKey(userId)),
                keys.sessionKeyPrefix(userId));
        deleteRoutesQuietly(membersBeforeRevoke);
    }

    /** 列出用户持有的令牌 */
    @Override
    public List<TokenSession> listByUserId(Long userId) {
        Assert.nonNull(userId, "用户ID不能为空");

        String indexKey = keys.userTokensKey(userId);
        Set<String> members = redisTemplate.opsForSet().members(indexKey);
        if (CollectionUtils.isEmpty(members)) {
            return new ArrayList<>();
        }

        List<String> storageTokens = new ArrayList<>(members);
        List<String> payloads = redisTemplate.opsForValue().multiGet(
                storageTokens.stream().map(h -> keys.sessionKeyByStorageToken(userId, h)).toList());

        List<TokenSession> result = new ArrayList<>(storageTokens.size());
        List<Object> staleMembers = new ArrayList<>();
        for (int i = 0; i < storageTokens.size(); i++) {
            String payload = payloads == null || i >= payloads.size() ? null : payloads.get(i);
            TokenSession session = tryFromJson(payload);
            if (session == null || !session.isValid() || session.isExpired()
                    || !userId.equals(session.getUserId())) {
                staleMembers.add(storageTokens.get(i));
                continue;
            }
            result.add(session);
        }

        if (!staleMembers.isEmpty()) {
            redisTemplate.opsForSet().remove(indexKey, staleMembers.toArray());
            for (Object stale : staleMembers) {
                redisTemplate.delete(keys.routeKeyByStorageToken(String.valueOf(stale)));
            }
        }
        return result;
    }

    /** 写入 token 散列→userId 路由键 */
    private void saveRoute(String storageHash, Long userId, long ttlMillis) {
        redisTemplate.opsForValue().set(
                keys.routeKeyByStorageToken(storageHash),
                String.valueOf(userId),
                Duration.ofMillis(ttlMillis));
    }

    /** 批量删除路由键（失败不抛出） */
    private void deleteRoutesQuietly(Set<String> storageHashes) {
        if (CollectionUtils.isEmpty(storageHashes)) {
            return;
        }
        List<String> routeKeys = new ArrayList<>(storageHashes.size());
        for (String hash : storageHashes) {
            routeKeys.add(keys.routeKeyByStorageToken(hash));
        }
        redisTemplate.delete(routeKeys);
    }

    /** 删除快照并从索引移除（补偿清理） */
    private void deleteSessionQuietly(Long userId, String storageHash) {
        try {
            redisTemplate.delete(keys.sessionKeyByStorageToken(userId, storageHash));
            redisTemplate.opsForSet().remove(keys.userTokensKey(userId), storageHash);
        } catch (Exception ignored) {
            // 补偿清理失败不掩盖原始异常
        }
    }

    /** 解析路由键中的用户 ID */
    private static Long parseRouteUserId(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            long userId = Long.parseLong(raw.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 计算会话 Redis TTL（毫秒） */
    private static long ttlMillis(Instant expireAt) {
        return Duration.between(Instant.now(), expireAt).toMillis();
    }

    /** 会话快照序列化为 JSON */
    private static String toJson(TokenSession session) {
        try {
            return MAPPER.writeValueAsString(session);
        } catch (JacksonException e) {
            throw UtilsException.exception(e, "序列化令牌会话失败");
        }
    }

    /** 尝试将 JSON 反序列化为会话快照 */
    private static TokenSession tryFromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, TokenSession.class);
        } catch (JacksonException e) {
            return null;
        }
    }

}
