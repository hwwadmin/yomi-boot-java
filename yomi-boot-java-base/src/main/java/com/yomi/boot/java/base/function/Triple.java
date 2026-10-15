package com.yomi.boot.java.base.function;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 三元组
 *
 * @author zwei
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Triple<A, B, C> {

    /** 第一个元素 */
    private A first;

    /** 第二个元素 */
    private B second;

    /** 第三个元素 */
    private C third;

    /** 创建三元组，第三个元素为 null */
    public static <A, B, C> Triple<A, B, C> of(A first, B second) {
        return new Triple<>(first, second, null);
    }

    /** 创建三元组 */
    public static <A, B, C> Triple<A, B, C> of(A first, B second, C third) {
        return new Triple<>(first, second, third);
    }

    /** 由二元组与第三个元素创建三元组 */
    public static <A, B, C> Triple<A, B, C> of(Pair<A, B> pair, C third) {
        return new Triple<>(pair.getFirst(), pair.getSecond(), third);
    }

    /** 由第一个元素与二元组创建三元组 */
    public static <A, B, C> Triple<A, B, C> of(A first, Pair<B, C> pair) {
        return new Triple<>(first, pair.getFirst(), pair.getSecond());
    }

    /** 按 first、second、third 逐项比较 */
    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Triple)) return false;
        Triple<?, ?, ?> other = (Triple<?, ?, ?>) o;
        if (!other.canEqual(this)) return false;
        final Object this$first = this.getFirst();
        final Object other$first = other.getFirst();
        if (!Objects.equals(this$first, other$first)) return false;
        final Object this$second = this.getSecond();
        final Object other$second = other.getSecond();
        if (!Objects.equals(this$second, other$second)) return false;
        final Object this$third = this.getThird();
        final Object other$third = other.getThird();
        return Objects.equals(this$third, other$third);
    }

    /** 供 {@link #equals} 使用的类型检查 */
    protected boolean canEqual(final Object other) {
        return other instanceof Triple;
    }

    /** 按 first、second、third 计算哈希 */
    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $first = this.getFirst();
        result = result * PRIME + ($first == null ? 43 : $first.hashCode());
        final Object $second = this.getSecond();
        result = result * PRIME + ($second == null ? 43 : $second.hashCode());
        final Object $third = this.getThird();
        result = result * PRIME + ($third == null ? 43 : $third.hashCode());
        return result;
    }

}
