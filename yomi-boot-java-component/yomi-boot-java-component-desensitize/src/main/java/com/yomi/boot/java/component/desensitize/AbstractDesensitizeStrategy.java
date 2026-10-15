package com.yomi.boot.java.component.desensitize;

import java.util.Objects;

/**
 * 脱敏策略基类：统一 null/空串约定，并提供按 code point 的保留与遮蔽能力
 * <p>
 * 通用约定：掩码字符固定 {@code *}；长度不足以保留前后位数时整体遮蔽（避免泄露）；
 * 按 code point 切分，不会切断代理对（emoji、生僻字）。
 *
 * @author zwei
 */
public abstract class AbstractDesensitizeStrategy implements DesensitizeStrategy {

    /** 掩码字符 */
    protected static final char MASK_CHAR = '*';

    /** 策略注册名（{@link DesensitizeStrategy#name()} 返回值） */
    private final String name;

    protected AbstractDesensitizeStrategy(String name) {
        this.name = Objects.requireNonNull(name, "策略名称不能为空");
    }

    /** 返回构造时注册的策略名 */
    @Override
    public final String name() {
        return name;
    }

    /** null/空串原样返回，否则委托 {@link #doMask} */
    @Override
    public final String mask(String value) {
        return (value == null || value.isEmpty()) ? value : doMask(value);
    }

    /**
     * 非空值的脱敏逻辑
     */
    protected abstract String doMask(String value);

    /**
     * 保留前 prefixKeep、后 suffixKeep 个字符（按 code point），其余替换为掩码字符
     */
    protected static String keepEdge(String value, int prefixKeep, int suffixKeep) {
        requireKeep(prefixKeep, suffixKeep);
        // long 相加：两个 int 相加可能溢出成负数，导致"长度不足整体遮蔽"判断失效
        long keepTotal = (long) prefixKeep + suffixKeep;
        int length = codePointLength(value);
        if (length <= keepTotal) {
            return repeatMask(length);
        }
        int suffixFrom = length - suffixKeep;
        return slice(value, 0, prefixKeep) + repeatMask(suffixFrom - prefixKeep) + slice(value, suffixFrom, length);
    }

    /**
     * 校验保留位数非负
     */
    protected static void requireKeep(int prefixKeep, int suffixKeep) {
        if (prefixKeep < 0 || suffixKeep < 0) {
            throw new IllegalArgumentException("保留位数不能为负数");
        }
    }

    /**
     * code point 个数
     */
    protected static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /**
     * 按 code point 截取 {@code [from, to)}
     */
    protected static String slice(String value, int from, int to) {
        return value.substring(value.offsetByCodePoints(0, from), value.offsetByCodePoints(0, to));
    }

    /**
     * 生成指定个数的掩码字符串
     */
    protected static String repeatMask(int count) {
        return String.valueOf(MASK_CHAR).repeat(count);
    }

}
