package com.yomi.boot.java.component.redis.ops.bloom;

/**
 * 布隆过滤器参数：位数组长度 m、哈希函数个数 k。
 *
 * @author zwei
 */
public record BloomFilterSpec(int bitSize, int hashFunctions) {

    /** ln(2)，用于布隆过滤器位数组长度计算 */
    private static final double LN_2 = Math.log(2);
    /** ln²(2)，用于布隆过滤器哈希函数个数计算 */
    private static final double LN_2_SQUARED = LN_2 * LN_2;

    /**
     * 根据期望插入量与可接受误判率计算 m、k。
     *
     * @param expectedInsertions       预计元素数量
     * @param falsePositiveProbability 误判率，取值 (0, 1)
     */
    public static BloomFilterSpec of(long expectedInsertions, double falsePositiveProbability) {
        if (expectedInsertions < 1) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (falsePositiveProbability <= 0 || falsePositiveProbability >= 1) {
            throw new IllegalArgumentException("falsePositiveProbability must be in (0, 1)");
        }
        int bitSize = optimalBitSize(expectedInsertions, falsePositiveProbability);
        int hashFunctions = optimalHashFunctions(expectedInsertions, bitSize);
        return new BloomFilterSpec(bitSize, hashFunctions);
    }

    /** 按预期元素数与误判率算位数组长度 */
    public static int optimalBitSize(long expectedInsertions, double falsePositiveProbability) {
        double m = -expectedInsertions * Math.log(falsePositiveProbability) / LN_2_SQUARED;
        long bitSize = (long) Math.ceil(m);
        if (bitSize < 1) {
            return 1;
        }
        if (bitSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "optimal bit size exceeds int range; reduce expectedInsertions or increase falsePositiveProbability");
        }
        return (int) bitSize;
    }

    /** 按位长与元素数算哈希函数个数 */
    public static int optimalHashFunctions(long expectedInsertions, long bitSize) {
        return Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * LN_2));
    }

}
