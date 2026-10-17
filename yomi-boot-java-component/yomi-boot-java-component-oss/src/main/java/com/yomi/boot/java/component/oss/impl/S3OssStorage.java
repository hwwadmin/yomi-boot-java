package com.yomi.boot.java.component.oss.impl;

import com.yomi.boot.java.component.oss.config.OssProperties;
import com.yomi.boot.java.component.oss.exception.OssErrorCode;
import com.yomi.boot.java.component.oss.exception.OssException;
import com.yomi.boot.java.component.oss.model.ObjectMeta;
import com.yomi.boot.java.component.oss.model.PutObjectOptions;
import com.yomi.boot.java.component.oss.support.OssErrorMapping;
import com.yomi.boot.java.component.oss.uri.OssUri;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * 基于 AWS SDK v2 S3 的 {@link com.yomi.boot.java.component.oss.OssStorage} 实现；
 * provider=s3 与 rustfs（S3 兼容存储）共用，后者经 {@link #forRustfs(OssProperties)} 构造。
 *
 * @author zwei
 */
public class S3OssStorage extends AbstractOssStorageSupport implements AutoCloseable {

    /** 对象不存在时的错误码关键字 */
    private static final Predicate<Throwable> NOT_FOUND = OssErrorMapping.s3NotFound();

    /** RustFS 未配置 region 时的缺省签名区域（RustFS 不校验区域，只要求收发一致） */
    private static final String DEFAULT_REGION = "us-east-1";

    /** S3 API 客户端 */
    private final S3Client client;

    /** 预签名 URL 生成 */
    private final S3Presigner presigner;

    public S3OssStorage(OssProperties properties) {
        this(properties,
                properties.getS3().getEndpoint(),
                properties.getS3().getRegion(),
                properties.getS3().isUsePathStyle(),
                properties.getS3().getAccessKeyId(),
                properties.getS3().getSecretAccessKey());
    }

    /** RustFS（S3 兼容）复用本实现：固定路径风格访问，region 未配置时按 {@value #DEFAULT_REGION} 签名 */
    public static S3OssStorage forRustfs(OssProperties properties) {
        OssProperties.Rustfs cfg = properties.getRustfs();
        return new S3OssStorage(properties,
                cfg.getEndpoint(),
                StringUtils.isBlank(cfg.getRegion()) ? DEFAULT_REGION : cfg.getRegion(),
                true,
                cfg.getAccessKeyId(),
                cfg.getSecretAccessKey());
    }

    private S3OssStorage(OssProperties properties, String endpoint, String region, boolean usePathStyle,
                         String accessKeyId, String secretAccessKey) {
        super(properties.getPublicBaseUrl(), properties.maxPresignExpiryOrDefault());
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId.trim(), secretAccessKey.trim()));
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(usePathStyle)
                .build();
        var builder = S3Client.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        if (StringUtils.isNotBlank(endpoint)) {
            URI endpointUri = URI.create(endpoint.trim());
            builder.endpointOverride(endpointUri);
            presignerBuilder.endpointOverride(endpointUri);
        }
        this.client = builder.build();
        this.presigner = presignerBuilder.build();
    }

    /** 关闭 S3 客户端与预签名器 */
    @Override
    public void close() {
        presigner.close();
        client.close();
    }

    /** 上传对象（要求已知 contentLength） */
    @Override
    public String putObject(String bucket, String key, InputStream body, long contentLength, PutObjectOptions options) {
        requireBody(body);
        // AWS SDK 的 RequestBody 要求已知长度（负值构造期即抛），这里显式拒绝并给出清晰错误码
        if (contentLength < 0) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT,
                    "S3 上传要求已知对象长度：contentLength < 0（长度未知）不被支持，请改用 alioss provider");
        }
        OssUri.BucketKey bk = OssUri.normalizeBucketKey(bucket, key);
        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                    .bucket(bk.bucket())
                    .key(bk.key());
            String contentType = contentTypeOrNull(options);
            if (contentType != null) {
                requestBuilder.contentType(contentType);
            }
            RequestBody requestBody = RequestBody.fromInputStream(body, contentLength);
            client.putObject(requestBuilder.build(), requestBody);
            return OssUri.join(bk.bucket(), bk.key());
        } catch (Exception ex) {
            throw mapError(ex, "S3 上传对象失败");
        }
    }

    /** 下载对象流 */
    @Override
    public InputStream getObject(String uri) {
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build());
        } catch (Exception ex) {
            throw mapError(ex, "S3 下载对象失败");
        }
    }

    /** HEAD 查询元数据 */
    @Override
    public ObjectMeta statObject(String uri) {
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            HeadObjectResponse out = client.headObject(HeadObjectRequest.builder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build());
            return ObjectMeta.builder()
                    .contentType(out.contentType())
                    .contentLength(out.contentLength() != null ? out.contentLength() : 0L)
                    .eTag(out.eTag())
                    .lastModified(out.lastModified())
                    .build();
        } catch (Exception ex) {
            throw mapError(ex, "S3 查询对象失败");
        }
    }

    /** 删除对象，不存在时按成功返回 */
    @Override
    public void deleteObject(String uri) {
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bk.bucket())
                    .key(bk.key())
                    .build());
        } catch (Exception ex) {
            if (OssErrorMapping.isNotFound(ex, NOT_FOUND)) {
                return;
            }
            throw mapError(ex, "S3 删除对象失败");
        }
    }

    /** 生成限时 GET 预签名 URL */
    @Override
    public String presignGet(String uri, Duration expiry) {
        validatePresignExpiry(expiry);
        OssUri.BucketKey bk = OssUri.parse(uri);
        try {
            var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bk.bucket())
                            .key(bk.key())
                            .build())
                    .build());
            return presigned.url().toString();
        } catch (Exception ex) {
            throw mapError(ex, "S3 生成预签名 URL 失败");
        }
    }

    /** 将 SDK 异常映射为组件 OssException */
    private static OssException mapError(Exception ex, String message) {
        return OssErrorMapping.map(ex, message, NOT_FOUND);
    }

}
