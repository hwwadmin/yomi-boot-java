package com.yomi.boot.java.base.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 加密类型枚举
 * <p>
 * algorithmName 均为合法的 JCA 标准算法名，可直接用于
 * {@code Cipher.getInstance} / {@code KeyPairGenerator.getInstance} / {@code MessageDigest.getInstance}。
 * 标记 {@code @Deprecated} 的算法已被证明不安全，仅供旧系统/旧数据兼容，禁止在新代码中使用。
 *
 * @author zwei
 */
@Getter
public enum EncryptType {

    // --------- 单向哈希算法 --------- //
    /** 已碰撞可伪造，仅用于旧数据兼容或非安全场景（如缓存键） */
    @Deprecated MD5("MD5"),
    /** 已碰撞可伪造，仅用于旧数据兼容 */
    @Deprecated SHA1("SHA-1"),
    /** SHA-256 摘要 */
    SHA256("SHA-256"),
    /** SHA3-256 摘要 */
    SHA3_256("SHA3-256"),

    // --------- 对称加密 --------- //
    /** AES 对称加密 */
    AES("AES"),
    /** 56位密钥可暴力破解，仅用于旧数据兼容 */
    @Deprecated DES("DES"),
    /** 64位分组受Sweet32攻击，NIST已弃用，仅用于旧系统兼容 */
    @Deprecated DESede("DESede"),
    /** 已被证明不安全，仅用于旧系统兼容 */
    @Deprecated RC4("RC4"),

    // --------- 非对称加密算法 --------- //
    /** RSA 非对称加密 */
    RSA("RSA"),
    /** DSA 数字签名 */
    DSA("DSA"),
    /** 椭圆曲线（ECDSA/ECDH），比RSA更高效更安全，推荐代替RSA和DSA */
    EC("EC"),

    // --------- 消息认证码 --------- //
    /** HMAC-SHA256 */
    HMAC_SHA256("HmacSHA256")

    ;

    /** JCA 标准算法名 */
    private final String algorithmName;

    EncryptType(String algorithmName) {
        this.algorithmName = algorithmName;
    }

    /**
     * 按 JCA 算法名查找枚举；空或未知名返回 {@code null}
     */
    public static EncryptType of(String algorithmName) {
        if (StringUtils.isBlank(algorithmName)) return null;
        for (EncryptType type : values()) {
            if (type.algorithmName.equals(algorithmName)) {
                return type;
            }
        }
        return null;
    }

}
