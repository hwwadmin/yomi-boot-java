package com.yomi.boot.java.base.enums;

import lombok.Getter;

/**
 * 消息认证码算法：HMAC（JDK）与 KMAC（Bouncy Castle）。
 *
 * @author zwei
 */
@Getter
public enum HmacAlgorithm {

    /** 默认：HMAC-SHA256 */
    HMAC_SHA256("HmacSHA256", false),

    /** HMAC-SHA3-256 */
    HMAC_SHA3_256("HmacSHA3-256", false),

    /** KMAC128，输出 128 位，需 BC Provider */
    KMAC128("KMAC128", true, 128, 16),

    /** KMAC256，输出 256 位，需 BC Provider */
    KMAC256("KMAC256", true, 256, 32),

    ;

    /** JCA {@link javax.crypto.Mac#getInstance(String)} 算法名 */
    private final String algorithmName;

    /** 是否为 KMAC 系列（须 Bouncy Castle） */
    private final boolean kmac;

    /** KMAC 输出长度（位）；非 KMAC 为 0 */
    private final int kmacOutputBits;

    /** KMAC 最小密钥字节数（KMAC128=16、KMAC256=32）；HMAC 为 0 */
    private final int kmacMinKeyBytes;

    HmacAlgorithm(String algorithmName, boolean kmac) {
        this(algorithmName, kmac, 0, 0);
    }

    HmacAlgorithm(String algorithmName, boolean kmac, int kmacOutputBits, int kmacMinKeyBytes) {
        this.algorithmName = algorithmName;
        this.kmac = kmac;
        this.kmacOutputBits = kmacOutputBits;
        this.kmacMinKeyBytes = kmacMinKeyBytes;
    }

}
