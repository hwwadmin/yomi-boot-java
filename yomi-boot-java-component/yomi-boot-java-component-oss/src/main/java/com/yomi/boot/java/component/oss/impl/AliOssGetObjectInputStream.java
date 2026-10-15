package com.yomi.boot.java.component.oss.impl;

import com.aliyun.sdk.service.oss2.models.GetObjectResult;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 关闭流时一并关闭 {@link GetObjectResult}，避免连接泄漏。
 *
 * @author zwei
 */
final class AliOssGetObjectInputStream extends FilterInputStream {

    /** 待关闭的 SDK 结果，释放连接 */
    private final GetObjectResult result;

    AliOssGetObjectInputStream(GetObjectResult result) throws IOException {
        super(requireBody(result));
        this.result = result;
    }

    /** 从 GetObject 结果取出响应体流 */
    private static InputStream requireBody(GetObjectResult result) throws IOException {
        InputStream body = result.body();
        if (body == null) {
            closeQuietly(result);
            throw new IOException("AliOSS 响应体为空");
        }
        return body;
    }

    /** 关闭响应体后关闭 GetObjectResult */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            closeQuietly(result);
        }
    }

    /** 关闭资源并吞掉 IOException */
    private static void closeQuietly(GetObjectResult result) throws IOException {
        try {
            result.close();
        } catch (Exception ex) {
            throw new IOException("关闭 AliOSS GetObjectResult 失败", ex);
        }
    }

}
