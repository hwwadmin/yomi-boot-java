package com.yomi.boot.java.component.oss.uri;

import com.yomi.boot.java.component.oss.exception.OssErrorCode;
import com.yomi.boot.java.component.oss.exception.OssException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 逻辑 URI（{@code bucket/key}）拼装与解析，与具体厂商无关。
 *
 * @author zwei
 */
public final class OssUri {

    private OssUri() {
    }

    /** 将 bucket 与 key 拼成可入库的 URI。key 前导 {@code /} 会去掉。 */
    public static String join(String bucket, String key) {
        BucketKey normalized = normalizeBucketKey(bucket, key);
        return normalized.bucket + "/" + normalized.key;
    }

    /**
     * 解析逻辑 URI。
     *
     * @throws OssException 格式非法或为空
     */
    public static BucketKey parse(String uri) {
        String trimmed = StringUtils.removeStart(StringUtils.trimToEmpty(uri), "/");
        if (trimmed.isEmpty()) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "uri 不能为空");
        }
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "uri 格式无效，应为 bucket/key");
        }
        String bucket = trimmed.substring(0, slash);
        String key = trimmed.substring(slash + 1);
        if (bucket.contains("/")) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "bucket 不能包含 /");
        }
        assertKeySafe(key);
        return new BucketKey(bucket, key);
    }

    /**
     * 用 {@code publicBaseUrl} 与逻辑 URI 拼固定访问 URL。
     * <p>路径按 URL 规则做百分号编码（保留 {@code /} 分隔）：中文、空格、{@code #}、{@code ?} 等
     * 原样拼接会让直链打不开或指向别的对象（{@code #} 之后被当 fragment 丢弃、{@code ?} 变 query）。
     * 对象键按原始文本处理——键里字面存在的 {@code %} 会被编码成 {@code %25}，属预期行为</p>
     */
    public static String buildObjectUrl(String publicBaseUrl, String uri) {
        parse(uri);
        String base = StringUtils.removeEnd(StringUtils.trimToEmpty(publicBaseUrl), "/");
        if (base.isEmpty()) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "yomi.oss.public-base-url 未配置");
        }
        String path = StringUtils.removeStart(StringUtils.trimToEmpty(uri), "/");
        return base + "/" + UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }

    /** 规范化并校验 bucket、key，供上传前使用。 */
    public static BucketKey normalizeBucketKey(String bucket, String key) {
        String b = StringUtils.trimToEmpty(bucket);
        String k = StringUtils.removeStart(StringUtils.trimToEmpty(key), "/");
        if (b.isEmpty()) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "bucket 不能为空");
        }
        if (k.isEmpty()) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "key 不能为空");
        }
        if (b.contains("/")) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "bucket 不能包含 /");
        }
        assertKeySafe(k);
        return new BucketKey(b, k);
    }

    /**
     * 对象键安全校验：拒绝 {@code .}/{@code ..} 段与空段 {@code //}
     * <p>浏览器、CDN、反向代理会按 RFC 3986 归一化点段，含点段的直链会指向另一个对象（或跨出桶前缀）；
     * 空段同理让 URL 与键不再一一对应，故在唯一入口（本类）拒绝</p>
     */
    private static void assertKeySafe(String key) {
        if (key.contains("//")) {
            throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "key 不能包含空段 //: " + key);
        }
        for (String segment : key.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw OssException.of(OssErrorCode.INVALID_ARGUMENT, "key 不能包含 . 或 .. 段: " + key);
            }
        }
    }

    /** 解析后的桶名与对象键 */
    public record BucketKey(String bucket, String key) {
    }

}
