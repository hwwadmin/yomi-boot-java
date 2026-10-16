package com.yomi.boot.java.component.httplog.collect;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应包装：tee 旁路缓存响应体（先写真实响应再写缓存副本，透传恒完整），并记录响应头
 * <p>
 * 刻意不用 Spring 截留式 {@code ContentCachingResponseWrapper}：截留式要求 finally 显式回写，
 * 漏调用会导致客户端收到空响应；tee 从结构上消除该风险。
 *
 * @author zwei
 */
public class CachingResponseWrapper extends HttpServletResponseWrapper {

    /** tee 旁路缓冲（上限内保留副本，写满仅标记截断） */
    private final TeeBytes teeBytes;

    /** 记录到的响应头（保持写入顺序；同名以最后值为准） */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /** 包装后的输出流 */
    private ServletOutputStream outputStream;

    /** 包装后的 Writer */
    private PrintWriter writer;

    public CachingResponseWrapper(HttpServletResponse response, int limit) {
        super(response);
        this.teeBytes = new TeeBytes(limit);
    }

    /** 返回 tee 输出流，多次调用同一实例 */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            outputStream = new TeeServletOutputStream(getResponse().getOutputStream());
        }
        return outputStream;
    }

    /** 基于 tee 输出流的字符写入器 */
    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), resolveCharset()));
        }
        return writer;
    }

    /** 写入响应并同步记录到 {@link #headers} */
    @Override
    public void setHeader(String name, String value) {
        super.setHeader(name, value);
        headers.put(name, value);
    }

    /** 追加响应头并记录（同名合并为逗号分隔） */
    @Override
    public void addHeader(String name, String value) {
        super.addHeader(name, value);
        headers.merge(name, value, (oldValue, newValue) -> oldValue + ", " + newValue);
    }

    /** 设置整型响应头并记录 */
    @Override
    public void setIntHeader(String name, int value) {
        super.setIntHeader(name, value);
        headers.put(name, String.valueOf(value));
    }

    /** 追加整型响应头并记录 */
    @Override
    public void addIntHeader(String name, int value) {
        super.addIntHeader(name, value);
        headers.merge(name, String.valueOf(value), (oldValue, newValue) -> oldValue + ", " + newValue);
    }

    /** 设置日期响应头并记录 */
    @Override
    public void setDateHeader(String name, long date) {
        super.setDateHeader(name, date);
        headers.put(name, String.valueOf(date));
    }

    /** 追加日期响应头并记录 */
    @Override
    public void addDateHeader(String name, long date) {
        super.addDateHeader(name, date);
        headers.merge(name, String.valueOf(date), (oldValue, newValue) -> oldValue + ", " + newValue);
    }

    /**
     * flush writer 缓冲（若业务通过本包装获取过 writer）
     * <p>writer 由本包装持有、不是容器创建的 writer，容器不会替其 flush——
     * 采集侧必须在读取缓存前调用，否则响应体会滞留在缓冲里（客户端收到空响应）。
     */
    public void flushWriterIfCreated() throws IOException {
        if (writer != null) {
            writer.flush();
        }
    }

    /** flush 本包装创建的 writer 后委托容器 */
    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    /** 已缓存的响应体文本；无数据返回 null */
    public String cachedBody() {
        byte[] bytes = teeBytes.toByteArray();
        if (bytes.length == 0) {
            return null;
        }
        return CachingRequestWrapper.decode(bytes, resolveCharset());
    }

    /** 设置类型并记录 Content-Type（容器不回调 setHeader） */
    @Override
    public void setContentType(String type) {
        super.setContentType(type);
        // 容器对 setContentType 内部直写头表、不回调 setHeader，这里显式记录
        if (type == null) {
            headers.remove("Content-Type");
        } else {
            headers.put("Content-Type", type);
        }
    }

    /** 记录到的响应头（副本）；Content-Type 以 getContentType 兜底（框架/容器绕过 setHeader 设置时） */
    public Map<String, String> cachedHeaders() {
        Map<String, String> snapshot = new LinkedHashMap<>(headers);
        String contentType = getResponse().getContentType();
        if (contentType != null && snapshot.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            snapshot.put("Content-Type", contentType);
        }
        return snapshot;
    }

    /** 是否被截断（仅影响日志副本，真实响应始终完整） */
    public boolean isTruncated() {
        return teeBytes.isTruncated();
    }

    /**
     * 响应字符集：优先 Content-Type 显式声明的 charset；JSON 类响应无 charset 时按规范取 UTF-8
     * <p>容器对不带 charset 的响应报 ISO-8859-1（Tomcat 实测），而 Spring MVC 的 JSON 响应是 UTF-8 字节，
     * 照容器值解码会把中文变成乱码；其余响应（如 text/html 无 charset）仍沿用容器值</p>
     */
    private Charset resolveCharset() {
        String contentType = getResponse().getContentType();
        Charset declared = declaredCharset(contentType);
        if (declared != null) {
            return declared;
        }
        if (isJsonContentType(contentType)) {
            return StandardCharsets.UTF_8;
        }
        String encoding = getResponse().getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 解析 Content-Type 声明的 charset；未声明或非法返回 null */
    private static Charset declaredCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            return MediaType.parseMediaType(contentType).getCharset();
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 类响应（application/json、application/*+json）：RFC 8259 规定文本编码为 UTF-8 */
    private static boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType)
                    || (mediaType.getSubtype() != null && mediaType.getSubtype().endsWith("+json"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 先写真实响应再旁路缓存：客户端数据完整性不依赖日志逻辑 */
    private final class TeeServletOutputStream extends ServletOutputStream {

        /** 容器原始响应体流 */
        private final ServletOutputStream delegate;

        private TeeServletOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        /** 写客户端并 tee 缓存单字节 */
        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            byte[] one = {(byte) b};
            teeBytes.tee(one, 0, 1);
        }

        /** 写客户端并 tee 缓存字节块 */
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            teeBytes.tee(b, off, len);
        }

        /** 委托原流 flush */
        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        /** 关闭原流 */
        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /** 委托原流 */
        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        /** 委托原流 */
        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

    }

}
