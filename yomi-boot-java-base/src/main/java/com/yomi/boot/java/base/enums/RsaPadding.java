package com.yomi.boot.java.base.enums;

import lombok.Getter;

import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;

/**
 * RSA 加解密时的填充方式（对应 JCA Cipher 变换名及 OAEP 参数）。
 *
 * @author zwei
 */
@Getter
public enum RsaPadding {

    /** 默认：OAEP，摘要与 MGF1 均为 SHA-256 */
    OAEP_SHA256("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"),

    /** PKCS#1 v1.5，仅旧系统兼容 */
    @Deprecated
    PKCS1("RSA/ECB/PKCS1Padding"),

    ;

    /** {@link javax.crypto.Cipher#getInstance(String)} 使用的变换名 */
    private final String transformation;

    RsaPadding(String transformation) {
        this.transformation = transformation;
    }

    /**
     * 传给 {@code Cipher.init} 的参数；PKCS1 无参数，返回 {@code null}。
     * <p>
     * OAEP 必须显式指定 MGF1 摘要：无参时 SunJCE 默认 MGF1 用 SHA-1，与 OpenSSL/WebCrypto 不兼容。
     */
    public AlgorithmParameterSpec getParameterSpec() {
        if (this == OAEP_SHA256) {
            return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        }
        return null;
    }

}
