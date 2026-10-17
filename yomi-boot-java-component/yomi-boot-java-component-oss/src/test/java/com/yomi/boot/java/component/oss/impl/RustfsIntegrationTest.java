package com.yomi.boot.java.component.oss.impl;

import com.yomi.boot.java.component.oss.OssStorage;
import com.yomi.boot.java.component.oss.config.OssProperties;
import com.yomi.boot.java.component.oss.factory.OssStorageFactory;
import com.yomi.boot.java.component.oss.model.ObjectMeta;
import com.yomi.boot.java.component.oss.model.PutObjectOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RustFS（S3 兼容）真机往返：走组件门面（provider=rustfs）完成上传 / 元数据 / 下载 / 预签名下载 / 删除。
 * <p>默认跳过；配置 {@code yomi.test.rustfs.endpoint}（或环境变量 {@code YOMI_TEST_RUSTFS_ENDPOINT}，
 * 凭据默认官方镜像的 rustfsadmin/rustfsadmin）后执行。本地起 RustFS：
 * {@code docker run -d -p 9000:9000 -p 9001:9001 rustfs/rustfs:1.0.1}</p>
 * <p>测试桶 {@code yomi-oss-it} 按需创建并保留（测试对象用例后清理）</p>
 *
 * @author zwei
 */
@EnabledIf("rustfsConfigured")
class RustfsIntegrationTest {

    /** 测试桶 */
    private static final String BUCKET = "yomi-oss-it";

    static boolean rustfsConfigured() {
        return RustfsTestSettings.configured();
    }

    @Test
    void objectRoundTripAgainstRustfs() throws Exception {
        String key = "it/" + UUID.randomUUID() + ".txt";
        byte[] payload = "rustfs roundtrip 中文".getBytes(StandardCharsets.UTF_8);

        try (S3Client admin = adminClient()) {
            ensureBucket(admin);
            try (OssStorage storage = OssStorageFactory.create(properties())) {
                String uri = storage.putObject(BUCKET, key, new ByteArrayInputStream(payload), payload.length,
                        PutObjectOptions.ofContentType("text/plain; charset=utf-8"));
                assertEquals(BUCKET + "/" + key, uri);

                ObjectMeta meta = storage.statObject(uri);
                assertEquals(payload.length, meta.getContentLength());
                assertNotNull(meta.getContentType());
                assertTrue(meta.getContentType().startsWith("text/plain"), "contentType: " + meta.getContentType());

                assertTrue(storage.exists(uri));
                try (InputStream in = storage.getObject(uri)) {
                    assertArrayEquals(payload, in.readAllBytes());
                }

                String presigned = storage.presignGet(uri, Duration.ofMinutes(2));
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(presigned)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode());
                assertArrayEquals(payload, response.body());

                storage.deleteObject(uri);
                assertFalse(storage.exists(uri));
                storage.deleteObject(uri);
            } finally {
                deleteObjectQuietly(admin, key);
            }
        }
    }

    /** 组件配置：provider=rustfs，region 不配（按 us-east-1 签名） */
    private static OssProperties properties() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("rustfs");
        OssProperties.Rustfs rustfs = properties.getRustfs();
        rustfs.setEndpoint(RustfsTestSettings.endpoint());
        rustfs.setAccessKeyId(RustfsTestSettings.accessKeyId());
        rustfs.setSecretAccessKey(RustfsTestSettings.secretAccessKey());
        return properties;
    }

    /** 桶管理用裸 SDK（组件门面不含建桶）；路径风格与组件保持一致 */
    private static S3Client adminClient() {
        return S3Client.builder()
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        RustfsTestSettings.accessKeyId(), RustfsTestSettings.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .endpointOverride(URI.create(RustfsTestSettings.endpoint()))
                .build();
    }

    /** 测试桶不存在时创建 */
    private static void ensureBucket(S3Client client) {
        boolean exists = client.listBuckets().buckets().stream().anyMatch(b -> BUCKET.equals(b.name()));
        if (!exists) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    /** 清理测试对象，失败不影响用例结论 */
    private static void deleteObjectQuietly(S3Client client, String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
        } catch (Exception ignored) {
            // 清理失败不影响用例结论
        }
    }

}
