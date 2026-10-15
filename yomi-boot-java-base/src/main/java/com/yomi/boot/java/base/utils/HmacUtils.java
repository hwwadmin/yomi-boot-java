package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.HmacAlgorithm;
import com.yomi.boot.java.base.exception.UtilsException;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jcajce.spec.KMACParameterSpec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Base64;
import java.util.Objects;

/**
 * 消息认证码工具类：HMAC（JDK）与 KMAC（Bouncy Castle）。
 * <p>
 * HMAC 默认 {@link HmacAlgorithm#HMAC_SHA256}。首次使用 KMAC 时会注册 BC Provider。
 *
 * @author zwei
 */
public abstract class HmacUtils {

    /** 未指定算法时使用的默认 HMAC */
    private static final HmacAlgorithm DEFAULT_HMAC = HmacAlgorithm.HMAC_SHA256;

    /** Bouncy Castle Provider 名称 */
    private static final String BC_PROVIDER = BouncyCastleProvider.PROVIDER_NAME;

    /** 注册 BC Provider 时的互斥锁 */
    private static final Object BC_LOCK = new Object();

    /** BC Provider 是否已注册 */
    private static volatile boolean bcRegistered;

    private HmacUtils() {
    }

    /**
     * HMAC-SHA256 计算 MAC。
     */
    public static byte[] mac(byte[] data, byte[] key) {
        return mac(data, key, DEFAULT_HMAC);
    }

    /**
     * HMAC 计算 MAC（不可用于 KMAC，请用 {@link #kmac}）。
     */
    public static byte[] mac(byte[] data, byte[] key, HmacAlgorithm algorithm) {
        Assert.nonNull(data, "data must not be null");
        Assert.nonNull(key, "key must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        Assert.isFalse(algorithm.isKmac(), "use kmac() for KMAC algorithms");
        try {
            Mac mac = Mac.getInstance(algorithm.getAlgorithmName());
            mac.init(new SecretKeySpec(key, algorithm.getAlgorithmName()));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "HMAC failed, algorithm={}", algorithm);
        }
    }

    /**
     * UTF-8 明文 HMAC-SHA256。
     */
    public static byte[] macUtf8(String text, byte[] key) {
        return macUtf8(text, key, DEFAULT_HMAC);
    }

    /**
     * UTF-8 明文 HMAC。
     */
    public static byte[] macUtf8(String text, byte[] key, HmacAlgorithm algorithm) {
        Assert.nonNull(text, "text must not be null");
        return mac(text.getBytes(StandardCharsets.UTF_8), key, algorithm);
    }

    /**
     * KMAC 计算 MAC（customization 传 {@code null} 时视为空字节数组）。
     * <p>
     * 密钥长度须满足 {@link HmacAlgorithm#getKmacMinKeyBytes()}，不足抛 {@link com.yomi.boot.java.base.exception.AssertException}。
     */
    public static byte[] kmac(byte[] data, byte[] key, byte[] customization, HmacAlgorithm algorithm) {
        Assert.nonNull(data, "data must not be null");
        Assert.nonNull(key, "key must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        Assert.isTrue(algorithm.isKmac(), "not a KMAC algorithm: {}", algorithm);
        Assert.isTrue(key.length >= algorithm.getKmacMinKeyBytes(),
                "KMAC key length must be >= {} bytes for {}, but was {}",
                algorithm.getKmacMinKeyBytes(), algorithm, key.length);
        byte[] s = Objects.requireNonNullElse(customization, new byte[0]);
        ensureBcProvider();
        try {
            Mac mac = Mac.getInstance(algorithm.getAlgorithmName(), BC_PROVIDER);
            mac.init(
                    new SecretKeySpec(key, "KMAC"),
                    new KMACParameterSpec(algorithm.getKmacOutputBits(), s)
            );
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "KMAC failed, algorithm={}", algorithm);
        }
    }

    /**
     * KMAC256（默认输出 256 位）。
     */
    public static byte[] kmac256(byte[] data, byte[] key, byte[] customization) {
        return kmac(data, key, customization, HmacAlgorithm.KMAC256);
    }

    /**
     * KMAC128（输出 128 位）。
     */
    public static byte[] kmac128(byte[] data, byte[] key, byte[] customization) {
        return kmac(data, key, customization, HmacAlgorithm.KMAC128);
    }

    /**
     * 校验 HMAC-SHA256（时序安全比较）。
     */
    public static boolean verify(byte[] data, byte[] key, byte[] expectedMac) {
        return verify(data, key, expectedMac, DEFAULT_HMAC);
    }

    /**
     * 校验 HMAC（时序安全比较）。
     */
    public static boolean verify(byte[] data, byte[] key, byte[] expectedMac, HmacAlgorithm algorithm) {
        Assert.nonNull(expectedMac, "expectedMac must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        Assert.isFalse(algorithm.isKmac(), "use verifyKmac() for KMAC");
        byte[] actual = mac(data, key, algorithm);
        return MessageDigest.isEqual(expectedMac, actual);
    }

    /**
     * 校验 KMAC（时序安全比较）。
     */
    public static boolean verifyKmac(byte[] data, byte[] key, byte[] customization, byte[] expectedMac,
                                     HmacAlgorithm algorithm) {
        Assert.nonNull(expectedMac, "expectedMac must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        Assert.isTrue(algorithm.isKmac(), "not a KMAC algorithm: {}", algorithm);
        byte[] actual = kmac(data, key, customization, algorithm);
        return MessageDigest.isEqual(expectedMac, actual);
    }

    /**
     * HMAC 结果的小写 Hex。
     */
    public static String macHex(byte[] data, byte[] key) {
        return macHex(data, key, DEFAULT_HMAC);
    }

    /**
     * HMAC 结果的小写 Hex。
     */
    public static String macHex(byte[] data, byte[] key, HmacAlgorithm algorithm) {
        return Hex.encodeHexString(mac(data, key, algorithm));
    }

    /**
     * HMAC 结果的标准 Base64。
     */
    public static String macBase64(byte[] data, byte[] key) {
        return macBase64(data, key, DEFAULT_HMAC);
    }

    /**
     * HMAC 结果的标准 Base64。
     */
    public static String macBase64(byte[] data, byte[] key, HmacAlgorithm algorithm) {
        return Base64.getEncoder().encodeToString(mac(data, key, algorithm));
    }

    /** 首次使用 KMAC 时注册 Bouncy Castle Provider（幂等） */
    private static void ensureBcProvider() {
        if (bcRegistered) {
            return;
        }
        synchronized (BC_LOCK) {
            if (Security.getProvider(BC_PROVIDER) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            bcRegistered = true;
        }
    }

}
