package com.yomi.boot.java.component.httplog.collect;

import org.springframework.util.FastByteArrayOutputStream;

import java.io.IOException;

/**
 * tee 旁路缓冲：上限内保留副本，写满仅标记截断（透传不受影响）
 *
 * @author zwei
 */
final class TeeBytes {

    /** 缓存上限（字节） */
    private final int limit;

    /** 缓存（最多 limit 字节） */
    private final FastByteArrayOutputStream cache = new FastByteArrayOutputStream(256);

    /** 是否已截断（缓存写满仍有后续数据） */
    private boolean truncated;

    TeeBytes(int limit) {
        this.limit = limit;
    }

    /** 缓存一段数据：超出上限的部分丢弃并标记截断 */
    void tee(byte[] b, int off, int len) throws IOException {
        if (truncated) {
            return;
        }
        int remaining = limit - cache.size();
        if (remaining <= 0) {
            truncated = true;
            return;
        }
        if (len > remaining) {
            cache.write(b, off, remaining);
            truncated = true;
        } else {
            cache.write(b, off, len);
        }
    }

    /** 已缓存的副本 */
    byte[] toByteArray() {
        return cache.toByteArray();
    }

    /** 是否被截断 */
    boolean isTruncated() {
        return truncated;
    }

}
