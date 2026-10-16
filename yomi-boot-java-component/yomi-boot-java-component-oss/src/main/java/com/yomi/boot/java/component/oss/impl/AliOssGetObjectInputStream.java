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

    /** 从 GetObject 结果取出响应体流，为空则先释放结果再报错 */
    private static InputStream requireBody(GetObjectResult result) throws IOException {
        InputStream body = result.body();
        if (body == null) {
            IOException failure = new IOException("AliOSS 响应体为空");
            try {
                result.close();
            } catch (Exception ex) {
                failure.addSuppressed(ex);
            }
            throw failure;
        }
        return body;
    }

    /** 关闭响应体后再关闭 GetObjectResult；两步都失败时保留前一个异常，后一个挂 suppressed */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            super.close();
        } catch (IOException ex) {
            failure = ex;
        }
        try {
            result.close();
        } catch (Exception ex) {
            IOException closeFailure = new IOException("关闭 AliOSS GetObjectResult 失败", ex);
            if (failure == null) {
                throw closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

}
