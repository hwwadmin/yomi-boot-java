package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.AesMode;
import com.yomi.boot.java.base.exception.UtilsException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AES 工具：各模式往返与 GCM 布局
 */
class AesUtilsTest {

    @Test
    void defaultGcmRoundTrip() {
        byte[] key = AesUtils.generateKey();
        byte[] plain = "hello-yomi".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = AesUtils.encrypt(plain, key);
        assertArrayEquals(plain, AesUtils.decrypt(cipher, key));
        assertEquals("hello-yomi", AesUtils.decryptToUtf8(AesUtils.encryptToBase64(plain, key), key));
    }

    @Test
    void gcmGoldenVectorLocksBlobLayout() {
        byte[] key = repeatByte(32, 0x11);
        byte[] iv = repeatByte(12, 0x22);
        byte[] plain = "yomi-aes-gcm".getBytes(StandardCharsets.UTF_8);
        byte[] blob = AesUtils.encrypt(plain, key, AesMode.AES_256_GCM, iv);
        assertEquals(12 + plain.length + 16, blob.length);
        String base64 = Base64.getEncoder().encodeToString(blob);
        assertEquals("IiIiIiIiIiIiIiIibphqIO2u+izIWL1RnLKPqBMPfKRMUus/tsOtoQ==", base64);
        assertArrayEquals(plain, AesUtils.decrypt(blob, key, AesMode.AES_256_GCM));
    }

    @Test
    void allModesRoundTrip() {
        byte[] plain = new byte[] {1, 2, 3, 4, 5};
        for (AesMode mode : AesMode.values()) {
            byte[] key = repeatByte(mode.getKeyLength(), 7);
            byte[] cipher;
            if (mode.usesIv()) {
                byte[] iv = repeatByte(mode.getIvLength(), 9);
                cipher = AesUtils.encrypt(plain, key, mode, iv);
            } else {
                cipher = AesUtils.encrypt(plain, key, mode, null);
            }
            assertArrayEquals(plain, AesUtils.decrypt(cipher, key, mode), "mode=" + mode);
        }
    }

    @Test
    void gcmTamperTagFails() {
        byte[] key = AesUtils.generateKey();
        byte[] cipher = AesUtils.encrypt("x".getBytes(StandardCharsets.UTF_8), key);
        cipher[cipher.length - 1] ^= 0x01;
        assertThrows(UtilsException.class, () -> AesUtils.decrypt(cipher, key));
    }

    @Test
    void wrongKeyLengthRejected() {
        byte[] key = new byte[16];
        assertThrows(Exception.class, () -> AesUtils.encrypt(new byte[1], key));
    }

    /** 非法 Base64 包装为 UtilsException（原实现逃逸原生 IllegalArgumentException） */
    @Test
    void decryptFromBase64RejectsInvalidBase64AsUtilsException() {
        byte[] key = AesUtils.generateKey();
        for (String bad : new String[] {"not base64!!!", "===="}) {
            UtilsException e = assertThrows(UtilsException.class, () -> AesUtils.decryptFromBase64(bad, key));
            assertEquals("invalid Base64 cipher blob", e.getMessage());
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
        assertThrows(UtilsException.class, () -> AesUtils.decryptToUtf8("!!!!", key));
    }

    @Test
    void emptyPlainAllowed() {
        byte[] key = AesUtils.generateKey();
        byte[] cipher = AesUtils.encrypt(new byte[0], key);
        assertEquals(0, AesUtils.decrypt(cipher, key).length);
    }

    @Test
    void generateIvRejectsEcb() {
        assertThrows(Exception.class, () -> AesUtils.generateIv(AesMode.AES_256_ECB));
    }

    private static byte[] repeatByte(int len, int value) {
        byte[] b = new byte[len];
        Arrays.fill(b, (byte) value);
        return b;
    }

}
