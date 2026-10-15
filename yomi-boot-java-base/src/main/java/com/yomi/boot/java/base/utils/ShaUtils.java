package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.ShaAlgorithm;
import com.yomi.boot.java.base.exception.UtilsException;
import org.apache.commons.codec.binary.Hex;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 单向摘要工具类（非 MAC、非密码存储）。
 * <p>
 * 默认 {@link ShaAlgorithm#SHA256}；Hex 为小写、无前缀。
 *
 * @author zwei
 */
public abstract class ShaUtils {

    /** 未指定算法时使用的默认摘要算法 */
    private static final ShaAlgorithm DEFAULT = ShaAlgorithm.SHA256;

    private ShaUtils() {
    }

    /**
     * 默认算法（SHA-256）计算摘要。
     */
    public static byte[] digest(byte[] data) {
        return digest(data, DEFAULT);
    }

    /**
     * 指定算法计算摘要。
     */
    public static byte[] digest(byte[] data, ShaAlgorithm algorithm) {
        Assert.nonNull(data, "data must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm.getAlgorithmName());
            return md.digest(data);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "digest failed, algorithm={}", algorithm);
        }
    }

    /**
     * UTF-8 字符串摘要（默认算法）。
     */
    public static byte[] digestUtf8(String text) {
        return digestUtf8(text, DEFAULT);
    }

    /**
     * UTF-8 字符串摘要。
     */
    public static byte[] digestUtf8(String text, ShaAlgorithm algorithm) {
        Assert.nonNull(text, "text must not be null");
        return digest(text.getBytes(StandardCharsets.UTF_8), algorithm);
    }

    /**
     * 摘要的小写 Hex 字符串。
     */
    public static String digestHex(byte[] data) {
        return digestHex(data, DEFAULT);
    }

    /**
     * 指定算法的小写 Hex 摘要。
     */
    public static String digestHex(byte[] data, ShaAlgorithm algorithm) {
        return Hex.encodeHexString(digest(data, algorithm));
    }

    /**
     * UTF-8 明文的小写 Hex 摘要（默认算法）。
     */
    public static String digestHexUtf8(String text) {
        return digestHexUtf8(text, DEFAULT);
    }

    /**
     * UTF-8 明文的小写 Hex 摘要。
     */
    public static String digestHexUtf8(String text, ShaAlgorithm algorithm) {
        Assert.nonNull(text, "text must not be null");
        return digestHex(text.getBytes(StandardCharsets.UTF_8), algorithm);
    }

    /**
     * 摘要的标准 Base64。
     */
    public static String digestBase64(byte[] data) {
        return digestBase64(data, DEFAULT);
    }

    /**
     * 指定算法的标准 Base64 摘要。
     */
    public static String digestBase64(byte[] data, ShaAlgorithm algorithm) {
        return Base64.getEncoder().encodeToString(digest(data, algorithm));
    }

}
