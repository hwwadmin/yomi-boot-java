package com.yomi.boot.java.component.token.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 令牌服务配置，对应 yml 前缀 yomi.token
 * <p>
 * 由 {@link TokenAutoConfiguration} 在 {@code yomi.token.enabled=true} 时绑定并校验；
 * 未启用时不注册，避免无效配置导致启动失败。
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.token")
public class TokenProperties {

    /** 是否启用令牌服务，未配置默认 false */
    private boolean enabled = false;

    /** 令牌有效期，签发时会话快照未指定过期时间时按此补齐 */
    private Duration ttl = Duration.ofHours(2);

    /** 是否允许并发登录；false 为单点登录，签发时按用户互踢 */
    private boolean allowMultiLogin = false;

    /** 令牌存储前缀，未配置时按 {@code yomi:token:{spring.application.name}} 补齐，多应用共用 Redis 时需保证唯一 */
    private String storePrefix;

    /** 无需令牌校验的接口路径（Spring PathPattern 语法，支持通配），不能含首尾空格 */
    private List<String> skipValidatePaths = new ArrayList<>();

    /** 单点登录签发时的等锁上限 */
    private Duration lockWait = Duration.ofSeconds(5);

    /** 单点登录签发时的持锁上限（等价于关闭 watchdog 的固定租期） */
    private Duration lockLease = Duration.ofSeconds(30);

    /**
     * 校验通过时是否按 {@link #ttl} 滑动续期（更新快照 expireAt 与 Redis TTL）；默认 false 保持固定过期时间
     */
    private boolean renewOnValidate = false;

    /**
     * 会话绝对存活上限（滑动续期不得超过 {@code issuedAt + maxLifetime}）；默认 1 天。
     * <p>{@link Duration#ZERO} 表示不限制（可无限续期），仅建议在明确接受该风险的场景使用；
     * 达到上限后停止续期，会话按既有过期时间结束，需重新登录</p>
     */
    private Duration maxLifetime = Duration.ofDays(1);

    /**
     * 进程内会话缓存 TTL；0 表示关闭这层（每次校验都读 Redis）。
     * <p>默认 5 秒：只做一层"削峰"薄缓存，不承担失效通道——撤销在发起实例上同步生效、
     * 其它实例最迟本值内生效。别调大</p>
     */
    private Duration localCacheTtl = Duration.ofSeconds(5);

    /**
     * 配置自检，在令牌服务启用时装配期调用
     */
    public void validate() {
        requirePositive(ttl, "yomi.token.ttl");
        requirePositive(lockWait, "yomi.token.lock-wait");
        requirePositive(lockLease, "yomi.token.lock-lease");
        if (maxLifetime == null || maxLifetime.isNegative()) {
            throw new IllegalStateException("yomi.token.max-lifetime 不能为负数（0 表示不限制）");
        }
        if (!maxLifetime.isZero() && maxLifetime.compareTo(ttl) < 0) {
            throw new IllegalStateException("yomi.token.max-lifetime 不能小于 yomi.token.ttl（0 表示不限制）");
        }
        if (localCacheTtl == null || localCacheTtl.isNegative()) {
            throw new IllegalStateException("yomi.token.local-cache-ttl 不能为负数（0 表示关闭进程内缓存）");
        }
        for (String skipPath : skipValidatePaths) {
            if (skipPath == null || !skipPath.equals(skipPath.trim())) {
                throw new IllegalStateException("yomi.token.skip-validate-paths 不能为空或含首尾空格: [" + skipPath + "]");
            }
        }
    }

    /** 校验 Duration 须大于 0 */
    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 必须大于 0");
        }
    }

}
