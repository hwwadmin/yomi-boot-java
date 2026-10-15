package com.yomi.boot.java.component.redis.ops.bloom;

import java.util.BitSet;

/**
 * 基于 {@link BitSet} 的布隆过滤器（内存实现）。
 * <p>
 * Hash 函数按固定顺序选取，只需指定个数即可，与 Redis 版 {@link BloomFilterRedisSupport} 共用同一套哈希逻辑。
 *
 * @author zwei
 */
public class BitSetBloomFilter {

    /** 位图存储 */
    private final BitSet bitSet;

    /** 位图长度 m */
    private final int bitSetSize;

    /** 设计容量 n */
    private final int expectedInsertions;

    /** 哈希函数个数 k */
    private final int hashFunctionNumber;

    /**
     * 按期望插入量与目标误判率计算 m、k 并创建过滤器。
     */
    public static BitSetBloomFilter create(long expectedInsertions, double falsePositiveProbability) {
        BloomFilterSpec spec = BloomFilterSpec.of(expectedInsertions, falsePositiveProbability);
        int n = expectedInsertions > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) expectedInsertions;
        return new BitSetBloomFilter(spec.bitSize(), n, spec.hashFunctions());
    }

    /**
     * 容量系数风格：位数组长度为 {@code ceil(c * n)}。
     *
     * @param c 容量系数，通常取 2
     * @param n 预计插入的元素个数
     * @param k 哈希函数个数
     */
    public static BitSetBloomFilter ofCoefficient(int c, int n, int k) {
        if (c < 1 || n < 1 || k < 1) {
            throw new IllegalArgumentException("c, n, k must be positive");
        }
        long bitSize = (long) Math.ceil(c * (double) n);
        if (bitSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bit size exceeds int range");
        }
        return new BitSetBloomFilter((int) bitSize, n, k);
    }

    public BitSetBloomFilter(int bitSize, int expectedInsertions, int hashFunctions) {
        if (bitSize < 1 || expectedInsertions < 1 || hashFunctions < 1) {
            throw new IllegalArgumentException("bitSize, expectedInsertions, hashFunctions must be positive");
        }
        this.hashFunctionNumber = hashFunctions;
        this.bitSetSize = bitSize;
        this.expectedInsertions = expectedInsertions;
        this.bitSet = new BitSet(this.bitSetSize);
    }

    /** 将元素映射到多个 bit 并置位 */
    public boolean add(String value) {
        if (contains(value)) {
            return false;
        }
        setBits(value);
        return true;
    }

    /** 各位均为 1 则判定可能存在 */
    public boolean contains(String value) {
        int[] hashes = createHashes(value, hashFunctionNumber);
        for (int hash : hashes) {
            if (!bitSet.get(bitIndex(hash, bitSetSize))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 估算当前参数下的误判率：(1 - e^(-k * n / m))^k
     */
    public double estimatedFalsePositiveProbability() {
        return Math.pow(
                (1 - Math.exp(-hashFunctionNumber * (double) expectedInsertions / bitSetSize)),
                hashFunctionNumber);
    }

    /** 位数组长度 */
    public int bitSetSize() {
        return bitSetSize;
    }

    /** 哈希函数个数 */
    public int hashFunctionNumber() {
        return hashFunctionNumber;
    }

    /**
     * 对字符串生成多路哈希值。
     */
    public static int[] createHashes(String value, int hashNumber) {
        if (hashNumber < 1) {
            throw new IllegalArgumentException("hashNumber must be positive");
        }
        int[] result = new int[hashNumber];
        for (int i = 0; i < hashNumber; i++) {
            result[i] = BloomHashUtil.hash(value, i);
        }
        return result;
    }

    static int bitIndex(int hash, int bitSetSize) {
        return Math.floorMod(hash, bitSetSize);
    }

    /** 按哈希结果设置布隆位图 */
    private void setBits(String value) {
        int[] hashes = createHashes(value, hashFunctionNumber);
        for (int hash : hashes) {
            bitSet.set(bitIndex(hash, bitSetSize), true);
        }
    }

}
