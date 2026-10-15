package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.AesMode;
import com.yomi.boot.java.base.exception.UtilsException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES 对称加解密工具类。
 * <p>
 * 默认 {@link AesMode#AES_256_GCM}。加密时 GCM/CBC 会自动生成随机 IV 并写入密文头部。
 * 密文布局：
 * <ul>
 *     <li>GCM：IV ‖ 密文 ‖ tag（tag 固定 16 字节在末尾）</li>
 *     <li>CBC：IV ‖ 密文</li>
 *     <li>ECB：仅密文（无 IV，不安全）</li>
 * </ul>
 *
 * @author zwei
 */
public abstract class AesUtils {

    /** 加密用安全随机数 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 未指定模式时使用的默认 AES 模式 */
    private static final AesMode DEFAULT_MODE = AesMode.AES_256_GCM;

    /** GCM 认证标签位数 */
    private static final int GCM_TAG_BITS = 128;

    private AesUtils() {
    }

    /**
     * 生成默认模式（AES-256-GCM）的随机密钥。
     */
    public static byte[] generateKey() {
        return generateKey(DEFAULT_MODE);
    }

    /**
     * 按模式生成随机密钥（长度见 {@link AesMode#getKeyLength()}）。
     */
    public static byte[] generateKey(AesMode mode) {
        Assert.nonNull(mode, "AES mode must not be null");
        byte[] key = new byte[mode.getKeyLength()];
        SECURE_RANDOM.nextBytes(key);
        return key;
    }

    /**
     * 生成默认模式的随机 IV。
     */
    public static byte[] generateIv() {
        return generateIv(DEFAULT_MODE);
    }

    /**
     * 生成随机 IV（长度见 {@link AesMode#getIvLength()}）；ECB 不支持。
     */
    public static byte[] generateIv(AesMode mode) {
        Assert.nonNull(mode, "AES mode must not be null");
        Assert.isTrue(mode.usesIv(), "mode {} does not use IV", mode);
        byte[] iv = new byte[mode.getIvLength()];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * 使用默认模式加密，返回完整密文 blob。
     */
    public static byte[] encrypt(byte[] plain, byte[] key) {
        return encrypt(plain, key, DEFAULT_MODE);
    }

    /**
     * 按指定模式加密；需要 IV 的模式会内部生成随机 IV。
     */
    public static byte[] encrypt(byte[] plain, byte[] key, AesMode mode) {
        Assert.nonNull(mode, "AES mode must not be null");
        if (mode.usesIv()) {
            return encrypt(plain, key, mode, generateIv(mode));
        }
        return encrypt(plain, key, mode, null);
    }

    /**
     * 使用默认模式解密。
     */
    public static byte[] decrypt(byte[] cipherBlob, byte[] key) {
        return decrypt(cipherBlob, key, DEFAULT_MODE);
    }

    /**
     * 按指定模式解密；blob 布局须与 {@link #encrypt(byte[], byte[], AesMode)} 一致。
     */
    public static byte[] decrypt(byte[] cipherBlob, byte[] key, AesMode mode) {
        Assert.nonNull(cipherBlob, "cipher blob must not be null");
        Assert.nonNull(key, "key must not be null");
        Assert.nonNull(mode, "AES mode must not be null");
        assertKeyLength(key, mode);
        try {
            if (mode.isGcm()) {
                return decryptGcm(cipherBlob, key, mode);
            }
            if (mode.usesIv()) {
                return decryptCbc(cipherBlob, key, mode);
            }
            return decryptEcb(cipherBlob, key, mode);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "AES decrypt failed, mode={}", mode);
        }
    }

    /**
     * 默认模式加密后做标准 Base64 编码。
     */
    public static String encryptToBase64(byte[] plain, byte[] key) {
        return Base64.getEncoder().encodeToString(encrypt(plain, key));
    }

    /**
     * 将 UTF-8 明文用默认模式加密后做标准 Base64 编码。
     */
    public static String encryptToBase64(String plainUtf8, byte[] key) {
        Assert.nonNull(plainUtf8, "plain text must not be null");
        return encryptToBase64(plainUtf8.getBytes(StandardCharsets.UTF_8), key);
    }

    /**
     * 解码 Base64 后用默认模式解密；Base64 非法时抛 {@link UtilsException}
     */
    public static byte[] decryptFromBase64(String base64, byte[] key) {
        Assert.nonNull(base64, "base64 must not be null");
        return decrypt(decodeBase64(base64, "cipher blob"), key);
    }

    /** 标准 Base64 解码；非法输入抛 {@link UtilsException} */
    private static byte[] decodeBase64(String base64, String what) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw UtilsException.exception(e, "invalid Base64 {}", what);
        }
    }

    /**
     * 解码 Base64 解密并转为 UTF-8 字符串。
     */
    public static String decryptToUtf8(String base64, byte[] key) {
        return new String(decryptFromBase64(base64, key), StandardCharsets.UTF_8);
    }

    /**
     * 使用指定 IV 加密（测试或与对端固定 IV 对齐时使用；GCM 下同一密钥禁止 IV 复用）。
     */
    static byte[] encrypt(byte[] plain, byte[] key, AesMode mode, byte[] iv) {
        Assert.nonNull(plain, "plain must not be null");
        Assert.nonNull(key, "key must not be null");
        Assert.nonNull(mode, "AES mode must not be null");
        assertKeyLength(key, mode);
        if (mode.usesIv()) {
            Assert.nonNull(iv, "IV must not be null for mode {}", mode);
            Assert.isTrue(iv.length == mode.getIvLength(), "IV length must be {}", mode.getIvLength());
        }
        try {
            Cipher cipher = Cipher.getInstance(mode.getTransformation());
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            if (mode.isGcm()) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] ctAndTag = cipher.doFinal(plain);
                return concat(iv, ctAndTag);
            }
            if (mode.usesIv()) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
                return concat(iv, cipher.doFinal(plain));
            }
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "AES encrypt failed, mode={}", mode);
        }
    }

    /** 解析 GCM 布局：IV + 密文与 tag */
    private static byte[] decryptGcm(byte[] cipherBlob, byte[] key, AesMode mode)
            throws GeneralSecurityException {
        int ivLen = mode.getIvLength();
        int tagLen = mode.getTagLength();
        Assert.isTrue(cipherBlob.length >= ivLen + tagLen, "cipher blob too short for GCM");
        byte[] iv = Arrays.copyOfRange(cipherBlob, 0, ivLen);
        byte[] ctAndTag = Arrays.copyOfRange(cipherBlob, ivLen, cipherBlob.length);
        Cipher cipher = Cipher.getInstance(mode.getTransformation());
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ctAndTag);
    }

    /** 解析 CBC 布局：IV + 密文 */
    private static byte[] decryptCbc(byte[] cipherBlob, byte[] key, AesMode mode)
            throws GeneralSecurityException {
        int ivLen = mode.getIvLength();
        Assert.isTrue(cipherBlob.length > ivLen, "cipher blob too short for CBC");
        byte[] iv = Arrays.copyOfRange(cipherBlob, 0, ivLen);
        byte[] ct = Arrays.copyOfRange(cipherBlob, ivLen, cipherBlob.length);
        Cipher cipher = Cipher.getInstance(mode.getTransformation());
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(ct);
    }

    /** ECB：整段为密文 */
    private static byte[] decryptEcb(byte[] cipherBlob, byte[] key, AesMode mode)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(mode.getTransformation());
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(cipherBlob);
    }

    /** 校验密钥字节长度与模式要求一致 */
    private static void assertKeyLength(byte[] key, AesMode mode) {
        Assert.isTrue(key.length == mode.getKeyLength(), "key length must be {} bytes for {}", mode.getKeyLength(), mode);
    }

    /** 拼接两段字节数组 */
    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

}
