package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.RsaPadding;
import com.yomi.boot.java.base.enums.RsaSignatureAlgorithm;
import com.yomi.boot.java.base.exception.UtilsException;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 非对称加解密与数字签名工具类。
 * <p>
 * 新密钥默认模长 {@link #DEFAULT_KEY_SIZE}（3072）；加解密默认 {@link RsaPadding#OAEP_SHA256}；
 * 签名默认 {@link RsaSignatureAlgorithm#SHA256_RSA}。
 * RSA 只适合短数据，大报文请先用 {@link AesUtils} 加密内容，再用本类加密 AES 密钥。
 *
 * @author zwei
 */
public abstract class RsaUtils {

    /** {@link #generateKeyPair()} 使用的模长（位） */
    public static final int DEFAULT_KEY_SIZE = 3072;

    /** {@link #generateKeyPair(int)} 允许的最小模长（位） */
    public static final int MIN_KEY_SIZE = 2048;

    /** 未指定填充时使用的默认 RSA 填充 */
    private static final RsaPadding DEFAULT_PADDING = RsaPadding.OAEP_SHA256;

    /** 未指定算法时使用的默认签名算法 */
    private static final RsaSignatureAlgorithm DEFAULT_SIGNATURE = RsaSignatureAlgorithm.SHA256_RSA;

    /** JCA 密钥算法的标准名，用于 {@link KeyPairGenerator}/{@link KeyFactory} */
    private static final String KEY_ALGORITHM = "RSA";

    private RsaUtils() {
    }

    /**
     * 生成默认模长（3072 位）的 RSA 密钥对。
     */
    public static KeyPair generateKeyPair() {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    /**
     * 生成指定模长的 RSA 密钥对，{@code keySize} 取 {@link #MIN_KEY_SIZE} 及以上。
     */
    public static KeyPair generateKeyPair(int keySize) {
        Assert.isTrue(keySize >= MIN_KEY_SIZE, "RSA key size must be >= {}", MIN_KEY_SIZE);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(keySize);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA generateKeyPair failed, keySize={}", keySize);
        }
    }

    /**
     * 从 X.509 SubjectPublicKeyInfo 编码加载公钥。
     */
    public static PublicKey loadPublicKey(byte[] x509Encoded) {
        Assert.nonNull(x509Encoded, "public key bytes must not be null");
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(x509Encoded));
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA loadPublicKey failed");
        }
    }

    /**
     * 从 PKCS#8 编码加载私钥。
     */
    public static PrivateKey loadPrivateKey(byte[] pkcs8Encoded) {
        Assert.nonNull(pkcs8Encoded, "private key bytes must not be null");
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Encoded));
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA loadPrivateKey failed");
        }
    }

    /**
     * 公钥 DER 编码（X.509）。
     */
    public static byte[] encodePublicKey(PublicKey publicKey) {
        Assert.nonNull(publicKey, "public key must not be null");
        return publicKey.getEncoded();
    }

    /**
     * 私钥 DER 编码（PKCS#8）；按敏感数据处理，不做明文持久化与日志输出。
     */
    public static byte[] encodePrivateKey(PrivateKey privateKey) {
        Assert.nonNull(privateKey, "private key must not be null");
        return privateKey.getEncoded();
    }

    /**
     * 公钥 DER 的标准 Base64。
     */
    public static String encodePublicKeyToBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(encodePublicKey(publicKey));
    }

    /**
     * 私钥 DER 的标准 Base64。
     */
    public static String encodePrivateKeyToBase64(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(encodePrivateKey(privateKey));
    }

    /**
     * 从标准 Base64 解码后加载公钥；Base64 非法抛 {@link UtilsException}
     */
    public static PublicKey loadPublicKeyFromBase64(String base64) {
        Assert.nonNull(base64, "base64 must not be null");
        return loadPublicKey(decodeBase64(base64, "public key"));
    }

    /**
     * 从标准 Base64 解码后加载私钥；Base64 非法抛 {@link UtilsException}
     */
    public static PrivateKey loadPrivateKeyFromBase64(String base64) {
        Assert.nonNull(base64, "base64 must not be null");
        return loadPrivateKey(decodeBase64(base64, "private key"));
    }

    /**
     * 公钥加密（默认 OAEP-SHA256）；明文长度受模长与填充限制。
     */
    public static byte[] encrypt(byte[] data, PublicKey publicKey) {
        return encrypt(data, publicKey, DEFAULT_PADDING);
    }

    /**
     * 公钥加密，指定填充方式；非 RSA 公钥（如 EC/DSA）抛 {@link com.yomi.boot.java.base.exception.AssertException}。
     */
    public static byte[] encrypt(byte[] data, PublicKey publicKey, RsaPadding padding) {
        Assert.nonNull(data, "data must not be null");
        Assert.nonNull(publicKey, "public key must not be null");
        Assert.nonNull(padding, "padding must not be null");
        assertWithinEncryptLimit(data, publicKey, padding);
        try {
            Cipher cipher = Cipher.getInstance(padding.getTransformation());
            initCipher(cipher, Cipher.ENCRYPT_MODE, publicKey, padding);
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA encrypt failed, padding={}", padding);
        }
    }

    /**
     * 私钥解密（默认 OAEP-SHA256）。
     */
    public static byte[] decrypt(byte[] cipher, PrivateKey privateKey) {
        return decrypt(cipher, privateKey, DEFAULT_PADDING);
    }

    /**
     * 私钥解密，指定填充方式。
     */
    public static byte[] decrypt(byte[] cipher, PrivateKey privateKey, RsaPadding padding) {
        Assert.nonNull(cipher, "cipher must not be null");
        Assert.nonNull(privateKey, "private key must not be null");
        Assert.nonNull(padding, "padding must not be null");
        try {
            Cipher cipherInstance = Cipher.getInstance(padding.getTransformation());
            initCipher(cipherInstance, Cipher.DECRYPT_MODE, privateKey, padding);
            return cipherInstance.doFinal(cipher);
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA decrypt failed, padding={}", padding);
        }
    }

    /**
     * 公钥加密后做标准 Base64 编码。
     */
    public static String encryptToBase64(byte[] data, PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(encrypt(data, publicKey));
    }

    /**
     * 解码 Base64 后私钥解密；Base64 非法抛 {@link UtilsException}
     */
    public static byte[] decryptFromBase64(String base64, PrivateKey privateKey) {
        Assert.nonNull(base64, "base64 must not be null");
        return decrypt(decodeBase64(base64, "cipher"), privateKey);
    }

    /**
     * 私钥签名（默认 SHA256withRSA）。
     */
    public static byte[] sign(byte[] data, PrivateKey privateKey) {
        return sign(data, privateKey, DEFAULT_SIGNATURE);
    }

    /**
     * 私钥签名，指定算法。
     */
    public static byte[] sign(byte[] data, PrivateKey privateKey, RsaSignatureAlgorithm algorithm) {
        Assert.nonNull(data, "data must not be null");
        Assert.nonNull(privateKey, "private key must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        try {
            Signature signature = Signature.getInstance(algorithm.getAlgorithm());
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA sign failed, algorithm={}", algorithm);
        }
    }

    /**
     * 公钥验签（默认 SHA256withRSA）；签名不匹配或长度非法返回 false，入参与算法错误抛 {@link UtilsException}。
     */
    public static boolean verify(byte[] data, byte[] signatureBytes, PublicKey publicKey) {
        return verify(data, signatureBytes, publicKey, DEFAULT_SIGNATURE);
    }

    /**
     * 公钥验签，指定算法；签名不匹配或长度非法返回 false，入参与算法错误抛 {@link UtilsException}。
     */
    public static boolean verify(byte[] data, byte[] signatureBytes, PublicKey publicKey,
                                 RsaSignatureAlgorithm algorithm) {
        Assert.nonNull(data, "data must not be null");
        Assert.nonNull(signatureBytes, "signature must not be null");
        Assert.nonNull(publicKey, "public key must not be null");
        Assert.nonNull(algorithm, "algorithm must not be null");
        try {
            Signature signature = Signature.getInstance(algorithm.getAlgorithm());
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (SignatureException e) {
            // 签名长度与密钥模长不符等格式问题，按验签不通过处理
            return false;
        } catch (GeneralSecurityException e) {
            throw UtilsException.exception(e, "RSA verify failed, algorithm={}", algorithm);
        }
    }

    /** 标准 Base64 解码；非法输入抛 {@link UtilsException} */
    private static byte[] decodeBase64(String base64, String what) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw UtilsException.exception(e, "invalid Base64 {}", what);
        }
    }

    /** OAEP 显式指定 MGF1 摘要（SunJCE 默认 SHA-1，与对端不兼容）；PKCS1 无参数 */
    private static void initCipher(Cipher cipher, int opmode, Key key, RsaPadding padding)
            throws GeneralSecurityException {
        AlgorithmParameterSpec spec = padding.getParameterSpec();
        if (spec == null) {
            cipher.init(opmode, key);
        } else {
            cipher.init(opmode, key, spec);
        }
    }

    /** 加密前检查密钥类型与明文是否超过当前密钥与填充允许的最大长度 */
    private static void assertWithinEncryptLimit(byte[] data, PublicKey publicKey, RsaPadding padding) {
        Assert.isTrue(publicKey instanceof RSAKey, "public key must be an RSA key, but was {}",
                publicKey.getAlgorithm());
        int modulusBytes = ((RSAKey) publicKey).getModulus().bitLength() / 8;
        int maxPlain;
        if (padding == RsaPadding.OAEP_SHA256) {
            // OAEP-SHA256：k - 2*hLen - 2，hLen=32
            maxPlain = modulusBytes - 66;
        } else {
            // PKCS#1 v1.5
            maxPlain = modulusBytes - 11;
        }
        Assert.isTrue(data.length <= maxPlain, "RSA plaintext too long, max {} bytes for this key/padding", maxPlain);
    }

}
