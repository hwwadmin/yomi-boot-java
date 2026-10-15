package com.yomi.boot.java.qucik.starter.testidempotency.dto;

import lombok.*;

/**
 * 幂等测试单次「业务」返回体（会写入 Redis/DB 并重放）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TestIdempotencyInvokeResult {

    /** 本次占位成功时生成的 runId */
    private Long runId;

    /** 该 namespace+key 下 Supplier 实际执行次数（幂等生效时应恒为 1） */
    private int sideEffectCount;

}
