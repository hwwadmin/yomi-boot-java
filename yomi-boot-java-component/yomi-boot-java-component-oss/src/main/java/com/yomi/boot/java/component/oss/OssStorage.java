package com.yomi.boot.java.component.oss;

import com.yomi.boot.java.component.oss.model.ObjectMeta;
import com.yomi.boot.java.component.oss.model.PutObjectOptions;

import java.io.InputStream;
import java.time.Duration;

/**
 * 统一对象存储门面。
 * <p>
 * 业务侧持久化请存逻辑 URI（{@code bucket/key}，见 {@link com.yomi.boot.java.component.oss.uri.OssUri}），预签名 URL 用于临时访问。
 * 默认由 {@link com.yomi.boot.java.component.oss.config.OssAutoConfiguration} 按 {@code yomi.oss.provider} 装配实现，
 * 也可自行声明 {@link OssStorage} Bean 覆盖；自定义实现须实现 {@link #close()} 释放资源（无资源可空实现）。
 * <p>
 * 继承 {@link AutoCloseable}，容器销毁时会关闭底层 SDK 客户端。
 *
 * @author zwei
 */
public interface OssStorage extends AutoCloseable {

    /**
     * 上传对象。{@code body} 由调用方关闭；{@code contentLength &lt; 0} 表示长度未知
     * （S3 要求已知长度，传负值会抛 {@code OSS_INVALID_ARGUMENT}；AliOSS 支持未知长度）。
     * <p>key 会做安全校验：拒绝 {@code .}/{@code ..} 段与空段（{@code //}），保证直链经 URL 归一化后仍指向本对象</p>
     *
     * @return 可入库的逻辑 URI，不是完整 HTTP URL
     */
    String putObject(String bucket, String key, InputStream body, long contentLength, PutObjectOptions options);

    /** 上传对象，无额外选项。 */
    default String putObject(String bucket, String key, InputStream body, long contentLength) {
        return putObject(bucket, key, body, contentLength, null);
    }

    /**
     * 下载对象。
     *
     * @return 对象流，由调用方在 finally 或 try-with-resources 中关闭；未关闭会占用连接
     */
    InputStream getObject(String uri);

    /**
     * 查询元数据。
     *
     * @throws com.yomi.boot.java.component.oss.exception.OssException 对象不存在时为 NOT_FOUND
     */
    ObjectMeta statObject(String uri);

    /** 判断对象是否存在；查询失败（非不存在）仍会抛异常。 */
    boolean exists(String uri);

    /** 删除对象；目标不存在时视为成功（幂等）。 */
    void deleteObject(String uri);

    /**
     * 拼接固定公开访问 URL（无签名、无过期）。
     * <p>
     * 依赖 {@code yomi.oss.public-base-url}，不校验对象是否真实存在。
     */
    String objectUrl(String uri);

    /**
     * 生成 GET 预签名 URL。
     * <p>
     * {@code expiry} 须大于 0 且不超过 {@code yomi.oss.max-presign-expiry}；不校验对象是否存在。
     */
    String presignGet(String uri, Duration expiry);

}
