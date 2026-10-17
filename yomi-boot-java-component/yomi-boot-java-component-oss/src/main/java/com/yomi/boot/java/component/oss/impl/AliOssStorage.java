package com.yomi.boot.java.component.oss.impl;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.*;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.yomi.boot.java.component.oss.config.OssProperties;
import com.yomi.boot.java.component.oss.exception.OssException;
import com.yomi.boot.java.component.oss.model.ObjectMeta;
import com.yomi.boot.java.component.oss.model.PutObjectOptions;
import com.yomi.boot.java.component.oss.support.OssErrorMapping;
import com.yomi.boot.java.component.oss.uri.OssUri;
import org.apache.commons.lang3.StringUtils;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Predicate;

/**
 * 基于阿里云 OSS Java SDK V2 的 {@link com.yomi.boot.java.component.oss.OssStorage} 实现。
 *
 * @author zwei
 */
public class AliOssStorage extends AbstractOssStorageSupport implements AutoCloseable {

    /** 对象不存在时的错误码关键字 */
    private static final Predicate<Throwable> NOT_FOUND = OssErrorMapping.aliOssNotFound();

    /** 阿里云 OSS V2 客户端 */
    private final OSSClient client;

    public AliOssStorage(OssProperties properties) {
        super(properties.getPublicBaseUrl(), properties.maxPresignExpiryOrDefault());
        OssProperties.AliOss cfg = properties.getAlioss();
        var builder = OSSClient.newBuilder()
                .region(cfg.getRegion().trim())
                .credentialsProvider(new StaticCredentialsProvider(
                        cfg.getAccessKeyId().trim(), cfg.getSecretAccessKey().trim()));
        if (StringUtils.isNotBlank(cfg.getEndpoint())) {
            builder.endpoint(cfg.getEndpoint().trim());
        }
        if (cfg.isUsePathStyle()) {
            builder.usePathStyle(true);
        }
        this.client = builder.build();
    }

    /** 释放 SDK 客户端 */
    @Override
    public void close() throws Exception {
        client.close();
    }

    /** 上传对象，返回 bucket/key URI */
    @Override
    public String putObject(String bucket, String key, InputStream body, long contentLength, PutObjectOptions options) {
        requireBody(body);
        OssUri.BucketKey bk = OssUri.normalizeBucketKey(bucket, key);
        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.newBuilder()
                    .bucket(bk.bucket())
                    .key(bk.key());
            String contentType = contentTypeOrNull(options);
            if (contentType != null) {
                requestBuilder.contentType(contentType);
            }
            if (contentLength >= 0) {
                requestBuilder.body(BinaryData.fromStream(body, contentLength));
                if (contentLength <= Integer.MAX_VALUE) {
                    requestBuilder.contentLength((int) contentLength);
                }
            } else {
                requestBuilder.body(BinaryData.fromStream(body));
            }
            client.putObject(requestBuilder.build());
            return OssUri.join(bk.bucket(), bk.key());
        } catch (Exception ex) {
            throw mapError(ex, "AliOSS 上传对象失败");
        }
    }

    /** 下载对象，流关闭时释放底层连接 */
    @Override
    public InputStream getObject(String uri) {
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build());
            return new AliOssGetObjectInputStream(result);
        } catch (Exception ex) {
            throw mapError(ex, "AliOSS 下载对象失败");
        }
    }

    /** HEAD 查询元数据 */
    @Override
    public ObjectMeta statObject(String uri) {
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            HeadObjectResult out = client.headObject(HeadObjectRequest.newBuilder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build());
            return toMeta(out.contentType(), out.contentLength(), out.eTag(), out.lastModified());
        } catch (Exception ex) {
            throw mapError(ex, "AliOSS 查询对象失败");
        }
    }

    /** 删除对象，不存在时按成功返回 */
    @Override
    public void deleteObject(String uri) {
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            client.deleteObject(DeleteObjectRequest.newBuilder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build());
        } catch (Exception ex) {
            if (OssErrorMapping.isNotFound(ex, NOT_FOUND)) {
                return;
            }
            throw mapError(ex, "AliOSS 删除对象失败");
        }
    }

    /** 生成限时 GET 预签名 URL */
    @Override
    public String presignGet(String uri, Duration expiry) {
        validatePresignExpiry(expiry);
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            var presigned = client.presign(GetObjectRequest.newBuilder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build(), PresignOptions.newBuilder().expiration(expiry).build());
            return presigned.url();
        } catch (Exception ex) {
            throw mapError(ex, "AliOSS 生成预签名 URL 失败");
        }
    }

    /** 将 Head/Get 响应字段转为 {@link ObjectMeta} */
    private static ObjectMeta toMeta(String contentType, Long contentLength, String eTag, String lastModified) {
        Instant modified = parseLastModified(lastModified);
        return ObjectMeta.builder()
                .contentType(contentType)
                .contentLength(contentLength != null ? contentLength : 0L)
                .eTag(eTag)
                .lastModified(modified)
                .build();
    }

    /**
     * SDK 返回的 lastModified 字符串转 Instant，解析失败返回 null。
     * <p>优先按 HTTP 日期（RFC 1123）解析，失败时回退 ISO-8601</p>
     */
    static Instant parseLastModified(String lastModified) {
        if (StringUtils.isBlank(lastModified)) {
            return null;
        }
        String value = lastModified.trim();
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // 非 RFC 1123：回退 ISO-8601
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 将 SDK 异常映射为组件 OssException */
    private static OssException mapError(Exception ex, String message) {
        return OssErrorMapping.map(ex, message, NOT_FOUND);
    }

}
