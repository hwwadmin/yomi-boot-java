package com.yomi.boot.java.component.oss.config;

import lombok.Data;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 对象存储配置，前缀 {@code yomi.oss}。
 * <p>
 * 由 {@link OssAutoConfiguration} 在 {@code yomi.oss.enabled=true} 时绑定并校验；
 * 未启用时不注册 Bean，子段配置可不填。
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.oss")
public class OssProperties {

    /** 预签名 URL 默认最长有效期 */
    private static final Duration DEFAULT_MAX_PRESIGN_EXPIRY = Duration.ofMinutes(30);

    /** 是否启用对象存储，未配置默认 false */
    private boolean enabled = false;

    /** 实现类型：s3、rustfs、alioss */
    private String provider;

    /** 固定访问 URL 的全局基址，多桶共用；仅预签名时可不配 */
    private String publicBaseUrl;

    /** 预签名有效期上限；null 或 0 表示使用默认 30 分钟 */
    private Duration maxPresignExpiry = DEFAULT_MAX_PRESIGN_EXPIRY;

    /** provider=s3 时使用 */
    private S3 s3 = new S3();

    /** provider=rustfs 时使用 */
    private Rustfs rustfs = new Rustfs();

    /** provider=alioss 时使用 */
    private AliOss alioss = new AliOss();

    /** 启用时按 provider 校验必填项 */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (maxPresignExpiry != null && maxPresignExpiry.isNegative()) {
            throw new IllegalStateException("yomi.oss.max-presign-expiry 不能小于 0");
        }
        OssProvider resolved = OssProvider.fromConfig(provider);
        switch (resolved) {
            case S3 -> s3.validate();
            case RUSTFS -> rustfs.validate();
            case ALIOSS -> alioss.validate();
        }
    }

    /** 预签名上限，未配置时返回 30 分钟 */
    public Duration maxPresignExpiryOrDefault() {
        if (maxPresignExpiry == null || maxPresignExpiry.isZero()) {
            return DEFAULT_MAX_PRESIGN_EXPIRY;
        }
        return maxPresignExpiry;
    }

    /** 解析后的 provider，非法值在 {@link #validate()} 或本方法中抛错 */
    public OssProvider resolvedProvider() {
        return OssProvider.fromConfig(provider);
    }

    /** AWS S3 连接配置 */
    @Data
    public static class S3 {

        /** AWS 区域 */
        private String region;

        /** 访问密钥 ID */
        private String accessKeyId;

        /** 不参与 toString，日志/Actuator 打印时不会泄露密钥 */
        @ToString.Exclude
        private String secretAccessKey;

        /** 自定义端点，空表示官方 AWS */
        private String endpoint;

        /** 访问 S3 兼容存储（Ceph RGW、SeaweedFS 等）时通常设为 true */
        private boolean usePathStyle;

        void validate() {
            requireNonBlank(region, "yomi.oss.s3.region");
            requireNonBlank(accessKeyId, "yomi.oss.s3.access-key-id");
            requireNonBlank(secretAccessKey, "yomi.oss.s3.secret-access-key");
        }
    }

    /** RustFS（S3 兼容，自建/本地部署）连接配置 */
    @Data
    public static class Rustfs {

        /** 服务地址，含 scheme，如 http://127.0.0.1:9000 */
        private String endpoint;

        /** 访问密钥 ID（官方 Docker 镜像默认 rustfsadmin） */
        private String accessKeyId;

        /** 不参与 toString，日志/Actuator 打印时不会泄露密钥 */
        @ToString.Exclude
        private String secretAccessKey;

        /** 空时按 us-east-1 签名（RustFS 不校验区域） */
        private String region;

        void validate() {
            requireNonBlank(endpoint, "yomi.oss.rustfs.endpoint");
            requireNonBlank(accessKeyId, "yomi.oss.rustfs.access-key-id");
            requireNonBlank(secretAccessKey, "yomi.oss.rustfs.secret-access-key");
        }
    }

    /** 阿里云 OSS 连接配置 */
    @Data
    public static class AliOss {

        /** 地域 */
        private String region;

        /** 访问密钥 ID */
        private String accessKeyId;

        /** 不参与 toString，日志/Actuator 打印时不会泄露密钥 */
        @ToString.Exclude
        private String secretAccessKey;

        /** 自定义端点，空表示按 region 使用官方域名 */
        private String endpoint;

        /** 是否使用路径风格访问 */
        private boolean usePathStyle;

        void validate() {
            requireNonBlank(region, "yomi.oss.alioss.region");
            requireNonBlank(accessKeyId, "yomi.oss.alioss.access-key-id");
            requireNonBlank(secretAccessKey, "yomi.oss.alioss.secret-access-key");
        }
    }

    /** 校验配置字符串非空 */
    private static void requireNonBlank(String value, String key) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException(key + " 不能为空");
        }
    }

}
