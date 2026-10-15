package com.yomi.boot.java.component.oss.impl;

import com.yomi.boot.java.component.oss.OssStorage;
import com.yomi.boot.java.component.oss.exception.OssErrorCode;
import com.yomi.boot.java.component.oss.exception.OssException;
import com.yomi.boot.java.component.oss.uri.OssUri;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * {@link OssStorage} 实现的公共逻辑：公开 URL、exists、上传/预签名校验。
 *
 * @author zwei
 */
abstract class AbstractOssStorageSupport implements OssStorage {

    /** 固定访问 URL 基址 */
    private final String publicBaseUrl;

    /** 预签名有效期上限 */
    private final Duration maxPresignExpiry;

    protected AbstractOssStorageSupport(String publicBaseUrl, Duration maxPresignExpiry) {
        this.publicBaseUrl = StringUtils.trimToEmpty(publicBaseUrl);
        this.maxPresignExpiry = maxPresignExpiry;
    }

    /** 按配置的 publicBaseUrl 拼对象访问地址 */
    @Override
    public String objectUrl(String uri) {
        return OssUri.buildObjectUrl(publicBaseUrl, uri);
    }

    /** 通过 stat 判断存在性，仅 NOT_FOUND 视为不存在 */
    @Override
    public boolean exists(String uri) {
        try {
            statObject(uri);
            return true;
        } catch (OssException ex) {
            if (ex.isNotFound()) {
                return false;
            }
            throw ex;
        }
    }

    /** 校验预签名时长在 (0, maxPresignExpiry] 内 */
    protected void validatePresignExpiry(Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "expiry 必须大于 0");
        }
        if (expiry.compareTo(maxPresignExpiry) > 0) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "expiry 超过允许上限");
        }
    }

    /** 上传流非 null，否则 INVALID_ARGUMENT */
    protected static void requireBody(java.io.InputStream body) {
        if (body == null) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "body 不能为 null");
        }
    }

    /** 从上传选项取出 Content-Type，未设置则返回 null */
    protected static String contentTypeOrNull(com.yomi.boot.java.component.oss.model.PutObjectOptions options) {
        if (options == null || StringUtils.isBlank(options.getContentType())) {
            return null;
        }
        return options.getContentType();
    }

}
