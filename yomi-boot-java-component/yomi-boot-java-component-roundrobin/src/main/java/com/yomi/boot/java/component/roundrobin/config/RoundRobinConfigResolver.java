package com.yomi.boot.java.component.roundrobin.config;

import com.yomi.boot.java.component.roundrobin.annotation.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinDefaults;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * 将 {@code @RoundRobinTask} 解析为运行时 {@link RoundRobinConfig}
 *
 * @author zwei
 */
public final class RoundRobinConfigResolver {

    private RoundRobinConfigResolver() {
    }

    /** 解析配置 */
    public static RoundRobinConfig resolve(RoundRobinTask annotation) {
        RoundRobinDefaults defaults = RoundRobinDefaults.builder().build();

        String name = annotation.name().trim();
        Duration frequency = parseRequired(annotation.frequency(), "frequency");
        RoundRobinConfig base = RoundRobinConfig.simple(name, frequency, defaults);

        var builder = RoundRobinConfig.builder()
                .name(name)
                .description(StringUtils.trimToNull(annotation.description()))
                .frequency(frequency)
                .randomDelayMin(mergeDuration(parseOptional(annotation.randomDelayMin()), base.getRandomDelayMin()))
                .randomDelayMax(mergeDuration(parseOptional(annotation.randomDelayMax()), base.getRandomDelayMax()))
                .wakeUpInterval(mergeDuration(parseOptional(annotation.wakeUpInterval()), base.getWakeUpInterval()))
                .execLockTtl(mergeDuration(parseOptional(annotation.execLockTtl()), base.getExecLockTtl()))
                .execWatchdog(resolveExecWatchdog(annotation.execWatchdog(), base.isExecWatchdog()))
                .maxHoldDuration(mergeOptionalDuration(parseOptional(annotation.maxHoldDuration()), base.getMaxHoldDuration()))
                .lockWait(mergeDuration(parseOptional(annotation.lockWait()), base.getLockWait()))
                .shutdownAwait(mergeDuration(parseOptional(annotation.shutdownAwait()), base.getShutdownAwait()));

        boolean randomDelayMaxCustom = parseOptional(annotation.randomDelayMax()) != null;
        boolean wakeUpIntervalCustom = parseOptional(annotation.wakeUpInterval()) != null;

        if (!randomDelayMaxCustom) {
            builder.randomDelayMax(Duration.ofMillis(100).plus(frequency));
        }
        if (!wakeUpIntervalCustom) {
            Duration wakeUp = frequency.dividedBy(2);
            if (wakeUp.compareTo(Duration.ofMillis(100)) < 0) {
                wakeUp = Duration.ofMillis(100);
            }
            builder.wakeUpInterval(wakeUp);
        }

        RoundRobinConfig config = builder.build();
        config.validate();
        return config;
    }

    /** 合并 Duration：方法级覆盖优先于默认值 */
    private static Duration mergeDuration(Duration override, Duration base) {
        return override != null ? override : base;
    }

    /**
     * 解析 execWatchdog：空/空白表示不覆盖默认；仅接受 true/false（忽略大小写与首尾空白），其它值报错。
     */
    private static boolean resolveExecWatchdog(String raw, boolean base) {
        if (StringUtils.isBlank(raw)) {
            return base;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("RoundRobinTask.execWatchdog 取值非法: [" + raw
                + "]，只支持 true/false（留空表示用默认值）");
    }

    /** 合并可选 Duration：未配置则沿用默认 */
    private static Duration mergeOptionalDuration(Duration override, Duration base) {
        return override != null ? override : base;
    }

    /** 解析必填 Duration，非法格式启动期报错 */
    private static Duration parseRequired(String raw, String field) {
        if (StringUtils.isBlank(raw)) {
            throw new IllegalStateException("RoundRobinTask." + field + " 不能为空");
        }
        return DurationStyle.detectAndParse(raw.trim());
    }

    /** 解析可选 Duration，空白表示不覆盖 */
    private static Duration parseOptional(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return DurationStyle.detectAndParse(raw.trim());
    }

}
