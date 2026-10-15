package com.yomi.boot.java.component.oss.impl;

import com.yomi.boot.java.component.oss.config.OssProperties;
import com.yomi.boot.java.component.oss.exception.OssErrorCode;
import com.yomi.boot.java.component.oss.exception.OssException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S3 实现的上传入参契约：长度未知（{@code contentLength < 0}）必须快速失败并给出明确错误码——
 * 不能把 -1 交给 AWS SDK（其 {@code RequestBody} 构造期就抛 IllegalArgumentException，
 * 还会被包装成"上传失败"，调用方看不出是参数问题还是网络问题）。
 */
class S3OssStorageTest {

    @Test
    void unknownContentLengthIsRejectedWithInvalidArgument() {
        try (S3OssStorage storage = new S3OssStorage(properties())) {
            OssException ex = assertThrows(OssException.class,
                    () -> storage.putObject("bucket", "dir/object.txt", body(), -1L));

            assertEquals(OssErrorCode.INVALID_ARGUMENT, ex.getErrorCode());
        }
    }

    @Test
    void illegalKeyIsRejectedBeforeSdkCall() {
        try (S3OssStorage storage = new S3OssStorage(properties())) {
            OssException ex = assertThrows(OssException.class,
                    () -> storage.putObject("bucket", "../escape", body(), 1L));

            assertEquals(OssErrorCode.INVALID_ARGUMENT, ex.getErrorCode());
        }
    }

    private static InputStream body() {
        return new ByteArrayInputStream(new byte[]{1});
    }

    /** 只填构造器用到的字段；SDK 客户端构造不发请求，测试离线 */
    private static OssProperties properties() {
        OssProperties properties = new OssProperties();
        properties.setPublicBaseUrl("https://cdn.example.com");
        OssProperties.S3 s3 = new OssProperties.S3();
        s3.setRegion("us-east-1");
        s3.setAccessKeyId("test-ak");
        s3.setSecretAccessKey("test-sk");
        properties.setS3(s3);
        return properties;
    }

}
