package com.yomi.boot.java.base.enums;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加密类型枚举：algorithmName 必须是可直接用于 JCA 的合法算法名
 */
class EncryptTypeTest {

    @Test
    void ellipticCurveAlgorithmNameIsValidForJca() {
        // 回归锁：历史上误用 "ECC"（非法 JCA 名），已更正为 "EC"
        assertEquals("EC", EncryptType.EC.getAlgorithmName());
        assertDoesNotThrow(() -> KeyPairGenerator.getInstance(EncryptType.EC.getAlgorithmName()));
    }

    @Test
    void sha3AlgorithmNameIsValidForJca() {
        // 回归锁：历史上误写 "SHA-3-256"（SUN 与 BouncyCastle 均不认，只能写成 "SHA3-256"）
        assertEquals("SHA3-256", EncryptType.SHA3_256.getAlgorithmName());
        assertDoesNotThrow(() -> MessageDigest.getInstance(EncryptType.SHA3_256.getAlgorithmName()));
        assertEquals(ShaAlgorithm.SHA3_256.getAlgorithmName(), EncryptType.SHA3_256.getAlgorithmName(),
                "两个枚举的同名算法必须写成同一串 JCA 名");
    }

    @Test
    void illegalEccNameIsRejectedByJca() {
        // "ECC" 不是任何标准 Provider 注册的算法名
        assertThrows(NoSuchAlgorithmException.class, () -> KeyPairGenerator.getInstance("ECC"));
    }

    @Test
    void algorithmNames() {
        assertEquals("MD5", EncryptType.MD5.getAlgorithmName());
        assertEquals("SHA-1", EncryptType.SHA1.getAlgorithmName());
        assertEquals("SHA-256", EncryptType.SHA256.getAlgorithmName());
        assertEquals("AES", EncryptType.AES.getAlgorithmName());
        assertEquals("HmacSHA256", EncryptType.HMAC_SHA256.getAlgorithmName());
        assertDoesNotThrow(() -> KeyPairGenerator.getInstance(EncryptType.RSA.getAlgorithmName()));
        assertDoesNotThrow(() -> {
            javax.crypto.Mac.getInstance(EncryptType.HMAC_SHA256.getAlgorithmName());
        });
    }

    @Test
    void ofByAlgorithmName() {
        assertSame(EncryptType.AES, EncryptType.of("AES"));
        assertSame(EncryptType.SHA3_256, EncryptType.of("SHA3-256"));
    }

    @Test
    void ofInvalidOrBlankReturnsNull() {
        assertNull(EncryptType.of(null));
        assertNull(EncryptType.of(" "));
        // 枚举名 ECC 不存在
        assertNull(EncryptType.of("ECC"));
        assertNull(EncryptType.of("UNKNOWN"));
    }

}
