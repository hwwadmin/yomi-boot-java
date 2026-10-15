package com.yomi.boot.java.base.enums;

import lombok.Getter;

/**
 * AES 工作模式：JCA 变换名、密钥/IV 长度及是否为 GCM。
 * <p>
 * 密文字节如何拼接见 {@link com.yomi.boot.java.base.utils.AesUtils}。
 *
 * @author zwei
 */
@Getter
public enum AesMode {

    /** 默认：256 位密钥 + GCM */
    AES_256_GCM("AES/GCM/NoPadding", 32, 12, 16, true),

    /** 128 位密钥 + GCM */
    AES_128_GCM("AES/GCM/NoPadding", 16, 12, 16, true),

    /** 256 位密钥 + CBC，仅旧数据兼容 */
    @Deprecated
    AES_256_CBC("AES/CBC/PKCS5Padding", 32, 16, 0, false),

    /** 128 位密钥 + CBC，仅旧数据兼容 */
    @Deprecated
    AES_128_CBC("AES/CBC/PKCS5Padding", 16, 16, 0, false),

    /** 256 位密钥 + ECB，不安全，仅兼容 */
    @Deprecated
    AES_256_ECB("AES/ECB/PKCS5Padding", 32, 0, 0, false),

    /** 128 位密钥 + ECB，不安全，仅兼容 */
    @Deprecated
    AES_128_ECB("AES/ECB/PKCS5Padding", 16, 0, 0, false),

    ;

    /** {@link javax.crypto.Cipher#getInstance(String)} 使用的变换名 */
    private final String transformation;

    /** 密钥字节长度（16 或 32） */
    private final int keyLength;

    /** IV 字节长度；ECB 为 0 */
    private final int ivLength;

    /** GCM 认证标签字节长度；非 GCM 为 0 */
    private final int tagLength;

    /** 是否为 GCM 模式 */
    private final boolean gcm;

    AesMode(String transformation, int keyLength, int ivLength, int tagLength, boolean gcm) {
        this.transformation = transformation;
        this.keyLength = keyLength;
        this.ivLength = ivLength;
        this.tagLength = tagLength;
        this.gcm = gcm;
    }

    /**
     * 当前模式加密时是否需要 IV（GCM、CBC 为 true，ECB 为 false）。
     */
    public boolean usesIv() {
        return ivLength > 0;
    }

}
