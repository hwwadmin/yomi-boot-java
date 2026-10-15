package com.yomi.boot.java.core.bc;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PasswordEncoder} 的 BCrypt 实现（强度、版本与盐可配置）。
 *
 * @author zwei
 */
@Slf4j
public class BCryptPasswordEncoder implements PasswordEncoder {

    /** BCrypt 哈希格式校验正则 */
    private static final Pattern BCRYPT_PATTERN = Pattern.compile("\\A\\$2(a|y|b)?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}");

    /** log rounds */
    private final int strength;

    /** 哈希版本前缀（$2a/$2b 等） */
    private final BCryptVersion version;

    /** 盐生成 */
    private final SecureRandom random;

    public BCryptPasswordEncoder() {
        this(-1);
    }

    public BCryptPasswordEncoder(int strength) {
        this(strength, null);
    }

    public BCryptPasswordEncoder(BCryptVersion version) {
        this(version, null);
    }

    public BCryptPasswordEncoder(BCryptVersion version, SecureRandom random) {
        this(version, -1, random);
    }

    public BCryptPasswordEncoder(int strength, SecureRandom random) {
        this(BCryptVersion.$2A, strength, random);
    }

    public BCryptPasswordEncoder(BCryptVersion version, int strength) {
        this(version, strength, null);
    }

    public BCryptPasswordEncoder(BCryptVersion version, int strength, SecureRandom random) {
        if (strength != -1 && (strength < BCrypt.MIN_LOG_ROUNDS || strength > BCrypt.MAX_LOG_ROUNDS)) {
            throw new IllegalArgumentException("Bad strength");
        }
        this.version = version;
        this.strength = (strength == -1) ? 10 : strength;
        this.random = random;
    }

    /** 对明文密码做 BCrypt 哈希 */
    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        String salt = getSalt();
        return BCrypt.hashpw(rawPassword.toString(), salt);
    }

    /** 生成 BCrypt 盐值 */
    private String getSalt() {
        if (this.random != null) {
            return BCrypt.gensalt(this.version.getVersion(), this.strength, this.random);
        }
        return BCrypt.gensalt(this.version.getVersion(), this.strength);
    }

    /** 明文与 BCrypt 哈希是否匹配；格式非法或校验异常时返回 false */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        if (encodedPassword == null || encodedPassword.length() == 0) {
            log.debug("密码为空");
            return false;
        }
        if (!BCRYPT_PATTERN.matcher(encodedPassword).matches()) {
            log.debug("密码格式不符合 BCrypt 规范");
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword.toString(), encodedPassword);
        } catch (IllegalArgumentException e) {
            log.warn("BCrypt 校验异常，按不匹配处理: {}", e.getMessage());
            return false;
        }
    }

    /** 已存哈希的工作因子低于当前配置强度时返回 true（建议重编码） */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.length() == 0) {
            log.debug("密码为空");
            return false;
        }
        Matcher matcher = BCRYPT_PATTERN.matcher(encodedPassword);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Encoded password does not look like BCrypt: " + encodedPassword);
        }
        int strength = Integer.parseInt(matcher.group(2));
        return strength < this.strength;
    }

    /**
     * Stores the default bcrypt version for use in configuration.
     *
     * @author Lin Feng
     */
    public enum BCryptVersion {

        $2A("$2a"),

        $2Y("$2y"),

        $2B("$2b");

        /** 写入哈希串的版本标识 */
        private final String version;

        BCryptVersion(String version) {
            this.version = version;
        }

        /** BCrypt 哈希串中的版本前缀 */
        public String getVersion() {
            return this.version;
        }

    }

}
