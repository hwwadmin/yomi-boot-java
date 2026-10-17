package com.yomi.boot.java.qucik.starter.testoss.service;

import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.oss.OssStorage;
import com.yomi.boot.java.component.oss.exception.OssErrorCode;
import com.yomi.boot.java.component.oss.exception.OssException;
import com.yomi.boot.java.component.oss.model.ObjectMeta;
import com.yomi.boot.java.component.oss.model.PutObjectOptions;
import com.yomi.boot.java.component.oss.uri.OssUri;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OSS 门面流程：仅依赖 {@link OssStorage}，不感知具体 provider 与连接配置。
 *
 * @author zwei
 */
@Service
@RequiredArgsConstructor
public class TestOssFlowService {

    /** 默认测试桶，须事先在对象存储侧创建 */
    public static final String DEFAULT_TEST_BUCKET = "yomi-test";

    /** 演示对象键前缀 */
    private static final String TEST_PREFIX = "test-oss/";
    /** 演示：上传对象选项 */
    private static final PutObjectOptions TEST_PUT_OPTIONS =
            PutObjectOptions.ofContentType("text/plain; charset=utf-8");
    /** 演示：预签名过期时间 */
    private static final Duration PRESIGN_EXPIRY = Duration.ofMinutes(5);

    /** 对象存储门面 */
    private final OssStorage ossStorage;

    /**
     * 执行上传 → 存在 → 元数据 → 下载 → 公开 URL（可跳过）→ 预签名 → 流程通过（对象保留，无需 DeleteObject 权限）。
     */
    public List<String> runFlow(String bucket) {
        Assert.isTrue(StringUtils.isNotBlank(bucket), "bucket 不能为空");
        byte[] payload = ("yomi-boot-java testoss " + IdUtils.nextId()).getBytes(StandardCharsets.UTF_8);
        List<String> steps = new ArrayList<>();
        String key = TEST_PREFIX + IdUtils.nextId() + ".txt";
        try {
            String uri = putObject(bucket, key, payload, steps);
            assertExists(uri, true, "[2.存在]");
            steps.add("2.存在: true, uri=" + uri);

            statObject(uri, payload.length, steps);
            readObject(uri, payload, steps);
            objectUrlOrSkip(uri, steps);
            presign(uri, steps);
            steps.add("7.流程通过");
            return steps;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("[OSS测试流程] 失败: " + ex.getMessage(), ex);
        }
    }

    /** 上传测试对象 */
    private String putObject(String bucket, String key, byte[] payload, List<String> steps) throws Exception {
        try (InputStream in = new ByteArrayInputStream(payload)) {
            String returned = ossStorage.putObject(bucket, key, in, payload.length, TEST_PUT_OPTIONS);
            String expected = OssUri.join(bucket, key);
            Assert.isTrue(returned.equals(expected), "[1.上传] URI 不一致: 期望={}, 实际={}", expected, returned);
            steps.add("1.上传: uri=" + returned + ", size=" + payload.length);
            return returned;
        }
    }

    /** 断言测试条件：Exists */
    private void assertExists(String uri, boolean expected, String label) {
        boolean actual = ossStorage.exists(uri);
        Assert.isTrue(actual == expected, "{} 期望 exists={}, 实际={}, uri={}", label, expected, actual, uri);
    }

    /** 拉取对象元数据并记录演示步骤 */
    private void statObject(String uri, int size, List<String> steps) {
        ObjectMeta meta = ossStorage.statObject(uri);
        Assert.isTrue(meta.getContentLength() == size,
                "[3.元数据] contentLength 不一致: 期望={}, 实际={}", size, meta.getContentLength());
        steps.add("3.元数据: contentLength=" + meta.getContentLength() + ", contentType=" + meta.getContentType());
    }

    /** 下载对象并校验内容 */
    private void readObject(String uri, byte[] payload, List<String> steps) throws Exception {
        try (InputStream in = ossStorage.getObject(uri)) {
            byte[] downloaded = in.readAllBytes();
            Assert.isTrue(Arrays.equals(payload, downloaded),
                    "[4.下载] 内容不一致, 期望长度={}, 实际长度={}", payload.length, downloaded.length);
            steps.add("4.下载: 内容与上传一致, size=" + downloaded.length);
        }
    }

    /** 获取公开 URL，未配置 public-base-url 时跳过 */
    private void objectUrlOrSkip(String uri, List<String> steps) {
        try {
            String url = ossStorage.objectUrl(uri);
            Assert.isTrue(StringUtils.isNotBlank(url), "[5.公开URL] 结果为空");
            steps.add("5.公开URL: " + url);
        } catch (OssException ex) {
            if (ex.getErrorCode() == OssErrorCode.INVALID_ARGUMENT
                    && ex.getMessage() != null && ex.getMessage().contains("public-base-url")) {
                steps.add("5.跳过: public-base-url 未配置");
                return;
            }
            throw ex;
        }
    }

    /** 生成预签名 URL 并校验 */
    private void presign(String uri, List<String> steps) {
        String url = ossStorage.presignGet(uri, PRESIGN_EXPIRY);
        Assert.isTrue(StringUtils.isNotBlank(url), "[6.预签名] URL 为空");
        steps.add("6.预签名: url已生成, expiry=" + PRESIGN_EXPIRY);
    }

}
