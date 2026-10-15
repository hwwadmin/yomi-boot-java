package com.yomi.boot.java.component.token.generator;

import java.util.UUID;

/**
 * 基于 UUID 的令牌生成器
 *
 * @author zwei
 */
public class UuidTokenGenerator implements TokenGenerator {

    /** 生成新的随机令牌字符串 */
    @Override
    public String newToken() {
        return UUID.randomUUID().toString();
    }

}
