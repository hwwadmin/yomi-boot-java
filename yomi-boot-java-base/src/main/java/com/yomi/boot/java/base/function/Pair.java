package com.yomi.boot.java.base.function;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 二元组
 *
 * @author zwei
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Pair<A, B> {

    /** 第一个元素 */
    private A first;

    /** 第二个元素 */
    private B second;

    /** 创建两个元素均为 null 的二元组 */
    public static <A, B> Pair<A, B> of() {
        return new Pair<>();
    }

    /** 创建二元组 */
    public static <A, B> Pair<A, B> of(A first, B second) {
        return new Pair<>(first, second);
    }

    /** 两个元素均为 null 时返回 true */
    public boolean isNull() {
        return Objects.isNull(first) && Objects.isNull(second);
    }

    /** 第一个元素为 null 时返回 true */
    public boolean isFirstNull() {
        return Objects.isNull(first);
    }

    /** 第二个元素为 null 时返回 true */
    public boolean isSecondNull() {
        return Objects.isNull(second);
    }

    /**
     * 都不为空
     */
    public boolean isNotNull() {
        return Objects.nonNull(first) && Objects.nonNull(second);
    }

    /** 按 first、second 逐项比较 */
    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Pair)) return false;
        Pair<?, ?> other = (Pair<?, ?>) o;
        if (!other.canEqual(this)) return false;
        if (!Objects.equals(this.getFirst(), other.getFirst())) return false;
        return Objects.equals(this.getSecond(), other.getSecond());
    }

    /** 供 {@link #equals} 使用的类型检查 */
    protected boolean canEqual(final Object other) {
        return other instanceof Pair;
    }

    /** 按 first、second 计算哈希 */
    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $first = this.getFirst();
        result = result * PRIME + ($first == null ? 43 : $first.hashCode());
        final Object $second = this.getSecond();
        result = result * PRIME + ($second == null ? 43 : $second.hashCode());
        return result;
    }

}
