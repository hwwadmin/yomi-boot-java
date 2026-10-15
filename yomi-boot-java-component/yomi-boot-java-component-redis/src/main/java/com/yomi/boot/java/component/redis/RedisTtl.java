package com.yomi.boot.java.component.redis;

/**
 * TTL 入参守卫
 * <p>同一个 0/负数在 Redis 与 Spring Data 的 Duration 语义下有三种相反含义，是静默事故高发点：
 * {@code SET key v PX 0} 写成永久键、{@code PEXPIRE key 0/-1} 直接删键、
 * {@code GETEX key PERSIST} 抹掉原有过期时间——统一挡在组件入口，只接受正数</p>
 *
 * @author zwei
 */
public final class RedisTtl {

    private RedisTtl() {
    }

    /**
     * 断言 TTL 为正数
     *
     * @param timeout 过期时长（单位由调用方决定）
     * @param method  调用方方法名，用于定位
     * @return 原值，便于内联到调用表达式
     */
    public static long requirePositive(long timeout, String method) {
        if (timeout <= 0) {
            throw new IllegalArgumentException(method + " 的 timeout 必须大于 0（当前: " + timeout
                    + "）：0/负数在 Redis 中会写成永久键、删除键或抹掉原过期时间；"
                    + "需要永久键请用不带 timeout 的重载，只读不续期请用 get");
        }
        return timeout;
    }

}
