package com.yomi.boot.java.component.oss.uri;

import com.yomi.boot.java.component.oss.exception.OssException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link OssUri} 拼装、解析与 URL 拼接 */
class OssUriTest {

    @Test
    void joinAndParse() {
        String uri = OssUri.join("my-bucket", "/path/to/file.txt");
        assertEquals("my-bucket/path/to/file.txt", uri);
        OssUri.BucketKey bk = OssUri.parse(uri);
        assertEquals("my-bucket", bk.bucket());
        assertEquals("path/to/file.txt", bk.key());
    }

    @Test
    void buildObjectUrl() {
        String url = OssUri.buildObjectUrl("https://cdn.example.com/", "b/k");
        assertEquals("https://cdn.example.com/b/k", url);
    }

    @Test
    void buildObjectUrlEncodesUnsafeCharacters() {
        // 中文与空格：不编码会打不开（空格非法、中文未编码）
        assertEquals("https://cdn.example.com/b/%E4%B8%AD%E6%96%87%20a.txt",
                OssUri.buildObjectUrl("https://cdn.example.com", "b/中文 a.txt"));
        // # 与 ?：不编码会被当 fragment/query，解析成另一个资源
        assertEquals("https://cdn.example.com/b/a%23b%3Fc",
                OssUri.buildObjectUrl("https://cdn.example.com", "b/a#b?c"));
        // 字面 % 是键的一部分：必须编码成 %25，否则被当转义前缀
        assertEquals("https://cdn.example.com/b/100%25.txt",
                OssUri.buildObjectUrl("https://cdn.example.com", "b/100%.txt"));
        // 目录分隔符保留：编码不得把 / 变成 %2F
        assertEquals("https://cdn.example.com/b/dir/sub/x",
                OssUri.buildObjectUrl("https://cdn.example.com", "b/dir/sub/x"));
    }

    @Test
    void invalidUriFails() {
        assertThrows(OssException.class, () -> OssUri.parse("only-bucket"));
    }

    @Test
    void dotAndEmptySegmentsAreRejected() {
        // 点段会被浏览器/CDN 按 RFC 3986 归一化：直链会指到另一个对象（或跨出桶前缀）
        assertThrows(OssException.class, () -> OssUri.parse("b/../x"));
        assertThrows(OssException.class, () -> OssUri.parse("b/./x"));
        assertThrows(OssException.class, () -> OssUri.parse("b/a//b"));
        // 上传入口同样拦下（直链迟早要用）
        assertThrows(OssException.class, () -> OssUri.normalizeBucketKey("b", "../x"));
        assertThrows(OssException.class, () -> OssUri.normalizeBucketKey("b", "a//b"));
        assertThrows(OssException.class, () -> OssUri.join("b", "./x"));
    }

    @Test
    void legalNestedAndNonAsciiKeysStillPass() {
        assertEquals("a/b/c", OssUri.parse("b/a/b/c").key());
        assertEquals("dir/sub/中文 a.txt", OssUri.normalizeBucketKey("b", "dir/sub/中文 a.txt").key());
    }

}
