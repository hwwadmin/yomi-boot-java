package com.yomi.boot.java.component.token.generator;

/**
 * 令牌生成器（令牌生成门面）
 * <p>
 * 默认实现为 {@link UuidTokenGenerator}；如需 JWT、短码等形态可自行实现并覆盖装配。
 *
 * @author zwei
 */
@FunctionalInterface
public interface TokenGenerator {

    /**
     * 生成新的令牌字符串
     */
    String newToken();

}
