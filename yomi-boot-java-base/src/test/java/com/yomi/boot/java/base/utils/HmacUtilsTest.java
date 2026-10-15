package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.HmacAlgorithm;
import com.yomi.boot.java.base.exception.AssertException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HMAC / KMAC 工具
 */
class HmacUtilsTest {

    @Test
    void hmacSha256RoundTripAndVerify() {
        byte[] key = "secret-key".getBytes(StandardCharsets.UTF_8);
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] mac = HmacUtils.mac(data, key);
        assertTrue(HmacUtils.verify(data, key, mac));
        assertFalse(HmacUtils.verify(data, key, copyAndFlip(mac)));
    }

    @Test
    void hmacSha3_256RoundTrip() {
        byte[] key = new byte[] {1, 2, 3, 4};
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        byte[] mac = HmacUtils.mac(data, key, HmacAlgorithm.HMAC_SHA3_256);
        assertTrue(HmacUtils.verify(data, key, mac, HmacAlgorithm.HMAC_SHA3_256));
    }

    @Test
    void kmac256DifferentCustomizationDifferentMac() {
        byte[] key = key(32);
        byte[] data = "msg".getBytes(StandardCharsets.UTF_8);
        byte[] mac1 = HmacUtils.kmac256(data, key, "ctx-a".getBytes(StandardCharsets.UTF_8));
        byte[] mac2 = HmacUtils.kmac256(data, key, "ctx-b".getBytes(StandardCharsets.UTF_8));
        assertFalse(java.security.MessageDigest.isEqual(mac1, mac2));
        assertTrue(HmacUtils.verifyKmac(data, key, "ctx-a".getBytes(StandardCharsets.UTF_8), mac1,
                HmacAlgorithm.KMAC256));
    }

    @Test
    void kmac128OutputLength() {
        byte[] mac = HmacUtils.kmac128(new byte[] {1}, key(16), null);
        assertEquals(16, mac.length);
    }

    /** NIST SP 800-185 §8.4.1：密钥不得短于安全强度（KMAC128 16 / KMAC256 32 字节），BC 自身不校验 */
    @Test
    void kmacRejectsKeyShorterThanSecurityStrength() {
        byte[] data = new byte[] {1};
        AssertException short128 = assertThrows(AssertException.class,
                () -> HmacUtils.kmac128(data, key(15), null));
        assertEquals("KMAC key length must be >= 16 bytes for KMAC128, but was 15", short128.getMessage());
        assertThrows(AssertException.class, () -> HmacUtils.kmac256(data, key(31), null));
        assertThrows(AssertException.class, () -> HmacUtils.verifyKmac(data, key(16), null, new byte[32],
                HmacAlgorithm.KMAC256));
        // 等于下限放行
        assertEquals(16, HmacUtils.kmac128(data, key(16), null).length);
        assertEquals(32, HmacUtils.kmac256(data, key(32), null).length);
    }

    @Test
    void macRejectsKmacAlgorithm() {
        assertThrows(AssertException.class,
                () -> HmacUtils.mac(new byte[1], new byte[1], HmacAlgorithm.KMAC256));
    }

    @Test
    void verifyRejectsNullAlgorithmWithAssert() {
        // 回归锁：null 算法原先在 algorithm.isKmac() 处直接 NPE，现已统一为 AssertException
        assertThrows(AssertException.class,
                () -> HmacUtils.verify(new byte[1], new byte[1], new byte[1], null));
        assertThrows(AssertException.class,
                () -> HmacUtils.verifyKmac(new byte[1], new byte[1], null, new byte[1], null));
    }

    /** RFC 4231 Test Case 2 */
    @Test
    void hmacRfc4231GoldenVector() {
        byte[] mac = HmacUtils.macUtf8("what do ya want for nothing?", "Jefe".getBytes(StandardCharsets.UTF_8));
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Hex.encodeHexString(mac));
    }

    /**
     * KMAC 黄金向量：密钥取 NIST SP 800-185 样例密钥（0x40 起 16 / 32 字节），空消息，customization
     * "My Tagged Application"；期望值经 OpenSSL 3.2.3 KMAC128 / KMAC256 交叉验证
     */
    @Test
    void kmacGoldenVectors() {
        byte[] customization = "My Tagged Application".getBytes(StandardCharsets.UTF_8);
        assertEquals("01d7f7d80bd8c78720c89162da4ae7f3",
                Hex.encodeHexString(HmacUtils.kmac128(new byte[0], key(16), customization)));
        assertEquals("d0ab718d0c211d4eaa2c679e5377c9ce9db61815057e3cb7c11556fc51d5f923",
                Hex.encodeHexString(HmacUtils.kmac256(new byte[0], key(32), customization)));
    }

    /** 内容为 0x40 起的密钥（NIST 样例同款） */
    private static byte[] key(int length) {
        byte[] key = new byte[length];
        for (int i = 0; i < length; i++) {
            key[i] = (byte) (0x40 + i);
        }
        return key;
    }

    private static byte[] copyAndFlip(byte[] mac) {
        byte[] copy = mac.clone();
        copy[0] ^= 0x01;
        return copy;
    }

}
