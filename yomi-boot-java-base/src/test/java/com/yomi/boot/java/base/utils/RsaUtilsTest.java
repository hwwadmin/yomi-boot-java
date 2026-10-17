package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.RsaPadding;
import com.yomi.boot.java.base.enums.RsaSignatureAlgorithm;
import com.yomi.boot.java.base.exception.AssertException;
import com.yomi.boot.java.base.exception.UtilsException;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSA 工具：默认 3072、加解密与签名
 */
class RsaUtilsTest {

    @Test
    void defaultKeySizeIs3072() {
        KeyPair pair = RsaUtils.generateKeyPair();
        assertEquals(3072, ((RSAPublicKey) pair.getPublic()).getModulus().bitLength());
    }

    @Test
    void oaepEncryptDecryptRoundTrip() {
        KeyPair pair = RsaUtils.generateKeyPair();
        byte[] plain = "rsa-short-secret".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = RsaUtils.encrypt(plain, pair.getPublic());
        assertArrayEquals(plain, RsaUtils.decrypt(cipher, pair.getPrivate()));
    }

    @Test
    void pkcs1DeprecatedStillWorks() {
        KeyPair pair = RsaUtils.generateKeyPair(2048);
        byte[] plain = "legacy".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = RsaUtils.encrypt(plain, pair.getPublic(), RsaPadding.PKCS1);
        assertArrayEquals(plain, RsaUtils.decrypt(cipher, pair.getPrivate(), RsaPadding.PKCS1));
    }

    @Test
    void signAndVerify() {
        KeyPair pair = RsaUtils.generateKeyPair();
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = RsaUtils.sign(data, pair.getPrivate());
        assertTrue(RsaUtils.verify(data, sig, pair.getPublic()));
        assertFalse(RsaUtils.verify(data, sig, RsaUtils.generateKeyPair().getPublic()));
    }

    @Test
    void keyEncodeRoundTrip() {
        KeyPair pair = RsaUtils.generateKeyPair();
        assertArrayEquals(
                RsaUtils.encodePublicKey(pair.getPublic()),
                RsaUtils.encodePublicKey(RsaUtils.loadPublicKeyFromBase64(RsaUtils.encodePublicKeyToBase64(pair.getPublic())))
        );
        assertArrayEquals(
                RsaUtils.encodePrivateKey(pair.getPrivate()),
                RsaUtils.encodePrivateKey(RsaUtils.loadPrivateKeyFromBase64(RsaUtils.encodePrivateKeyToBase64(pair.getPrivate())))
        );
    }

    @Test
    void verify2048PublicKeyWith3072GeneratedSigner() {
        KeyPair legacy = RsaUtils.generateKeyPair(2048);
        byte[] data = "cross".getBytes(StandardCharsets.UTF_8);
        byte[] sig = RsaUtils.sign(data, legacy.getPrivate(), RsaSignatureAlgorithm.SHA256_RSA);
        assertTrue(RsaUtils.verify(data, sig, legacy.getPublic()));
    }

    /**
     * 签名长度非法按"验签不通过"处理，返回 false
     */
    @Test
    void malformedSignatureReturnsFalse() {
        KeyPair pair = RsaUtils.generateKeyPair(2048);
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        assertFalse(RsaUtils.verify(data, new byte[10], pair.getPublic()));
        assertFalse(RsaUtils.verify(data, new byte[256], pair.getPublic()));
        byte[] otherKeySizeSig = RsaUtils.sign(data, RsaUtils.generateKeyPair().getPrivate());
        assertFalse(RsaUtils.verify(data, otherKeySizeSig, pair.getPublic()));
    }

    /**
     * Base64 解码失败包装为 UtilsException（与 AesUtils 一致）
     */
    @Test
    void base64DecodeFailuresAreUtilsException() {
        KeyPair pair = RsaUtils.generateKeyPair();
        assertEquals("invalid Base64 public key", assertThrows(UtilsException.class,
                () -> RsaUtils.loadPublicKeyFromBase64("not base64!!!")).getMessage());
        assertEquals("invalid Base64 private key", assertThrows(UtilsException.class,
                () -> RsaUtils.loadPrivateKeyFromBase64("====")).getMessage());
        assertEquals("invalid Base64 cipher", assertThrows(UtilsException.class,
                () -> RsaUtils.decryptFromBase64("not base64!!!", pair.getPrivate())).getMessage());
    }

    /**
     * 非 RSA 公钥抛 AssertException，消息含实际算法名
     */
    @Test
    void nonRsaPublicKeyThrowsAssertException() throws Exception {
        KeyPair ec = KeyPairGenerator.getInstance("EC").generateKeyPair();
        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);
        AssertException e = assertThrows(AssertException.class, () -> RsaUtils.encrypt(plain, ec.getPublic()));
        assertTrue(e.getMessage().contains("EC"), e.getMessage());
    }

    /**
     * 对端（OpenSSL/WebCrypto 等）约定 hash 与 MGF1 同为 SHA-256；
     * 双方显式传 spec 互解，RsaUtils 的 OAEP 参数与对端一致。
     */
    @Test
    void oaepInteropWithExplicitMgF1Sha256() throws Exception {
        KeyPair pair = RsaUtils.generateKeyPair(2048);
        byte[] plain = "interop".getBytes(StandardCharsets.UTF_8);

        Cipher peerEncrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        peerEncrypt.init(Cipher.ENCRYPT_MODE, pair.getPublic(), interopSpec());
        assertArrayEquals(plain, RsaUtils.decrypt(peerEncrypt.doFinal(plain), pair.getPrivate()));

        Cipher peerDecrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        peerDecrypt.init(Cipher.DECRYPT_MODE, pair.getPrivate(), interopSpec());
        assertArrayEquals(plain, peerDecrypt.doFinal(RsaUtils.encrypt(plain, pair.getPublic())));
    }

    private static OAEPParameterSpec interopSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

}
