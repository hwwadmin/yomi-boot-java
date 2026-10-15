package com.yomi.boot.java.base.enums;

import lombok.Getter;

/**
 * 单向摘要算法（用于 {@link java.security.MessageDigest}）。
 *
 * @author zwei
 */
@Getter
public enum ShaAlgorithm {

    /** 默认推荐 */
    SHA256("SHA-256"),

    SHA512("SHA-512"),

    SHA3_256("SHA3-256"),

    SHA3_512("SHA3-512"),

    /** 仅旧数据/缓存键等兼容，新代码禁用 */
    @Deprecated
    MD5("MD5"),

    ;

    /** JCA {@link java.security.MessageDigest#getInstance(String)} 算法名 */
    private final String algorithmName;

    ShaAlgorithm(String algorithmName) {
        this.algorithmName = algorithmName;
    }

}
