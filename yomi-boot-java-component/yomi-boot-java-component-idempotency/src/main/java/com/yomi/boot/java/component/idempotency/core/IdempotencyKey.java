package com.yomi.boot.java.component.idempotency.core;

import com.yomi.boot.java.base.utils.ShaUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 一次幂等请求的逻辑键
 * <p>namespace / idempotencyKey 是身份字段，原样存储与比对，长度上限与 t_sys_idempotency_record 列宽一致（超限由守卫点名拒绝）；
 * requestHash 只用于「同键不同体」比对，构造时统一取 SHA-256 摘要——指纹本身不限长，落库与缓存用的都是这个摘要
 *
 * @author zwei
 */
public record IdempotencyKey(String namespace, String idempotencyKey, String requestHash) {

    /** namespace 列宽上限 */
    public static final int MAX_NAMESPACE_LENGTH = 128;

    /** idempotency_key 列宽上限 */
    public static final int MAX_KEY_LENGTH = 256;

    /** 请求指纹摘要长度（SHA-256 hex，即 request_hash 列宽） */
    public static final int REQUEST_HASH_LENGTH = 64;

    /** 归一指纹：去空白后为空视为未传，非空一律转成摘要，列宽与缓存键长度都由此封顶 */
    public IdempotencyKey {
        String fingerprint = StringUtils.trimToNull(requestHash);
        requestHash = fingerprint == null ? null : ShaUtils.digestHexUtf8(fingerprint);
    }

    /** 命名空间与幂等键均非空 */
    public boolean isValid() {
        return StringUtils.isNotBlank(namespace) && StringUtils.isNotBlank(idempotencyKey);
    }

}
