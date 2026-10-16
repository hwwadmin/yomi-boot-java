package com.yomi.boot.java.component.httplog.collect;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 请求包装：tee 旁路缓存请求体（业务读取时顺带缓存，不额外读网络流），超上限截断并标记
 *
 * @author zwei
 */
public class CachingRequestWrapper extends HttpServletRequestWrapper {

    /** tee 旁路缓冲（上限内保留副本，写满仅标记截断） */
    private final TeeBytes teeBytes;

    /** 包装后的输入流（同一实例，保证多次 getInputStream 行为一致） */
    private ServletInputStream inputStream;

    public CachingRequestWrapper(HttpServletRequest request, int limit) {
        super(request);
        this.teeBytes = new TeeBytes(limit);
    }

    /** 返回 tee 输入流，多次调用同一实例 */
    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) {
            inputStream = new TeeServletInputStream(getRequest().getInputStream());
        }
        return inputStream;
    }

    /** 按 {@link #resolveCharset()} 解码的字符流 */
    @Override
    public BufferedReader getReader() throws IOException {
        Charset charset = resolveCharset();
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    /** 已缓存的请求体文本；无数据返回 null（截断处按字符边界回退） */
    public String cachedBody() {
        byte[] bytes = teeBytes.toByteArray();
        if (bytes.length == 0) {
            return null;
        }
        return decode(bytes, resolveCharset());
    }

    /** 是否被截断 */
    public boolean isTruncated() {
        return teeBytes.isTruncated();
    }

    /** 请求字符集，缺省或非法时 UTF-8 */
    private Charset resolveCharset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 解码；截断可能切断多字节字符，去掉尾部替换符 */
    static String decode(byte[] bytes, Charset charset) {
        String text = new String(bytes, charset);
        if (!text.isEmpty() && text.charAt(text.length() - 1) == '\uFFFD') {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 委托原流并旁路缓存的输入流 */
    private final class TeeServletInputStream extends ServletInputStream {

        /** 容器原始请求体流 */
        private final ServletInputStream delegate;

        private TeeServletInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        /** 单字节读取并 tee 缓存 */
        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                byte[] one = {(byte) b};
                teeBytes.tee(one, 0, 1);
            }
            return b;
        }

        /** 批量读取并 tee 缓存 */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                teeBytes.tee(b, off, n);
            }
            return n;
        }

        /** 委托原流 */
        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        /** 委托原流 */
        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        /** 委托原流 */
        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        /** 关闭原流 */
        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /** 委托原流 */
        @Override
        public int available() throws IOException {
            return delegate.available();
        }

    }

}
