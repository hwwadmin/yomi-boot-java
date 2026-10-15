package com.yomi.boot.java.component.redis.ops.bloom;

/**
 * 布隆过滤器常用 Hash 算法（与 Hutool {@code HashUtil} 中实现保持一致）
 *
 * @author zwei
 */
final class BloomHashUtil {

    private BloomHashUtil() {
    }

    static int hash(String str, int algorithmIndex) {
        return switch (algorithmIndex) {
            case 0 -> rsHash(str);
            case 1 -> jsHash(str);
            case 2 -> elfHash(str);
            case 3 -> bkdrHash(str);
            case 4 -> apHash(str);
            case 5 -> djbHash(str);
            case 6 -> sdbmHash(str);
            case 7 -> pjwHash(str);
            default -> fnvHash(str + algorithmIndex);
        };
    }

    static int rsHash(String str) {
        int b = 378551;
        int a = 63689;
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = hash * a + str.charAt(i);
            a = a * b;
        }
        return hash & 0x7FFFFFFF;
    }

    static int jsHash(String str) {
        int hash = 1315423911;
        for (int i = 0; i < str.length(); i++) {
            hash ^= ((hash << 5) + str.charAt(i) + (hash >> 2));
        }
        return Math.abs(hash) & 0x7FFFFFFF;
    }

    static int pjwHash(String str) {
        int bitsInUnsignedInt = 32;
        int threeQuarters = (bitsInUnsignedInt * 3) / 4;
        int oneEighth = bitsInUnsignedInt / 8;
        int highBits = 0xFFFFFFFF << (bitsInUnsignedInt - oneEighth);
        int hash = 0;
        int test;
        for (int i = 0; i < str.length(); i++) {
            hash = (hash << oneEighth) + str.charAt(i);
            if ((test = hash & highBits) != 0) {
                hash = ((hash ^ (test >> threeQuarters)) & (~highBits));
            }
        }
        return hash & 0x7FFFFFFF;
    }

    static int elfHash(String str) {
        int hash = 0;
        int x;
        for (int i = 0; i < str.length(); i++) {
            hash = (hash << 4) + str.charAt(i);
            if ((x = (int) (hash & 0xF0000000L)) != 0) {
                hash ^= (x >> 24);
                hash &= ~x;
            }
        }
        return hash & 0x7FFFFFFF;
    }

    static int bkdrHash(String str) {
        int seed = 131;
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = (hash * seed) + str.charAt(i);
        }
        return hash & 0x7FFFFFFF;
    }

    static int sdbmHash(String str) {
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = str.charAt(i) + (hash << 6) + (hash << 16) - hash;
        }
        return hash & 0x7FFFFFFF;
    }

    static int djbHash(String str) {
        int hash = 5381;
        for (int i = 0; i < str.length(); i++) {
            hash = ((hash << 5) + hash) + str.charAt(i);
        }
        return hash & 0x7FFFFFFF;
    }

    static int apHash(String str) {
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash ^= ((i & 1) == 0)
                    ? ((hash << 7) ^ str.charAt(i) ^ (hash >> 3))
                    : (~((hash << 11) ^ str.charAt(i) ^ (hash >> 5)));
        }
        return hash;
    }

    static int fnvHash(String data) {
        final int p = 16777619;
        int hash = (int) 2166136261L;
        for (int i = 0; i < data.length(); i++) {
            hash = (hash ^ data.charAt(i)) * p;
        }
        hash += hash << 13;
        hash ^= hash >> 7;
        hash += hash << 3;
        hash ^= hash >> 17;
        hash += hash << 5;
        return Math.abs(hash);
    }

}
