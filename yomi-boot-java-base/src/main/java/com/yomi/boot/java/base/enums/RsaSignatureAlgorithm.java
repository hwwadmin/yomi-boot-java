package com.yomi.boot.java.base.enums;

import lombok.Getter;

/**
 * RSA 数字签名算法名（用于 {@link java.security.Signature}）。
 *
 * @author zwei
 */
@Getter
public enum RsaSignatureAlgorithm {

    /** 默认：SHA-256 + RSA */
    SHA256_RSA("SHA256withRSA"),

    /** SHA-1 + RSA，仅旧系统兼容 */
    @Deprecated
    SHA1_RSA("SHA1withRSA"),

    ;

    /** {@link java.security.Signature#getInstance(String)} 使用的算法名 */
    private final String algorithm;

    RsaSignatureAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

}
