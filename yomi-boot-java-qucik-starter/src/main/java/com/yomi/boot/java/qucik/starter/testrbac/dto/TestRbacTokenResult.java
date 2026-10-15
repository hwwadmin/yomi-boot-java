package com.yomi.boot.java.qucik.starter.testrbac.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试用令牌签发结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestRbacTokenResult {

    /** 签发目标用户 */
    private long userId;

    /** 访问令牌 */
    private String token;

}
