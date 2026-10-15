package com.yomi.boot.java.component.idempotency.core;

import org.apache.commons.lang3.StringUtils;

/**
 * 一次幂等请求的逻辑键
 *
 * @author zwei
 */
public record IdempotencyKey(String namespace, String idempotencyKey, String requestHash) {

    /** 命名空间与幂等键均非空 */
    public boolean isValid() {
        return StringUtils.isNotBlank(namespace) && StringUtils.isNotBlank(idempotencyKey);
    }

}
