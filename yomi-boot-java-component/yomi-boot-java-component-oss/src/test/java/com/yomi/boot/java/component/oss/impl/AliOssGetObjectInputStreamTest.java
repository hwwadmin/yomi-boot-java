package com.yomi.boot.java.component.oss.impl;

import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载流的异常语义：close 顺带释放 {@link GetObjectResult}，失败如实抛出；
 * 响应体与结果都失败时保留先出现的异常。
 */
class AliOssGetObjectInputStreamTest {

    @Test
    void closeAlsoClosesResultBody() throws IOException {
        TrackingStream body = new TrackingStream();

        try (InputStream ignored = new AliOssGetObjectInputStream(resultOf(body))) {
            // 只验证 close 的释放行为
        }

        assertTrue(body.closed);
    }

    @Test
    void nullBodyFailsWithEmptyBodyMessage() {
        IOException ex = assertThrows(IOException.class,
                () -> new AliOssGetObjectInputStream(resultOf(null)));

        assertEquals("AliOSS 响应体为空", ex.getMessage());
    }

    @Test
    void bodyFailureStaysPrimaryWhenResultCloseAlsoFails() {
        ThrowingStream body = new ThrowingStream(1);

        IOException ex = assertThrows(IOException.class, () -> {
            AliOssGetObjectInputStream stream = new AliOssGetObjectInputStream(resultOf(body));
            stream.close();
        });

        assertEquals("body close failed #1", ex.getMessage(), "主异常应是响应体的失败");
        assertEquals(1, ex.getSuppressed().length);
        assertEquals("关闭 AliOSS GetObjectResult 失败", ex.getSuppressed()[0].getMessage());
    }

    @Test
    void resultCloseFailureAloneIsWrapped() {
        ThrowingStream body = new ThrowingStream(2);

        IOException ex = assertThrows(IOException.class, () -> {
            AliOssGetObjectInputStream stream = new AliOssGetObjectInputStream(resultOf(body));
            stream.close();
        });

        assertEquals("关闭 AliOSS GetObjectResult 失败", ex.getMessage());
        assertEquals("body close failed #2", ex.getCause().getMessage());
    }

    /** 结果体就是 body 流本身（SDK 的 body()/close() 均取 innerBody） */
    private static GetObjectResult resultOf(InputStream body) {
        return GetObjectResult.newBuilder().innerBody(body).build();
    }

    /** 只记录是否被关闭 */
    private static final class TrackingStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingStream() {
            super(new byte[0]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 从第 N 次 close 调用起抛异常，用来分别打到"响应体关闭"与"结果关闭"两步 */
    private static final class ThrowingStream extends ByteArrayInputStream {

        private final int failFromCall;

        private int closeCalls;

        private ThrowingStream(int failFromCall) {
            super(new byte[0]);
            this.failFromCall = failFromCall;
        }

        @Override
        public void close() throws IOException {
            if (++closeCalls >= failFromCall) {
                throw new IOException("body close failed #" + closeCalls);
            }
        }
    }

}
