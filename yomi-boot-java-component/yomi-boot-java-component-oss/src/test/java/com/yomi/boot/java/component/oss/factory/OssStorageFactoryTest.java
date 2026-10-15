package com.yomi.boot.java.component.oss.factory;

import com.yomi.boot.java.component.oss.OssStorage;
import com.yomi.boot.java.component.oss.config.OssProperties;
import com.yomi.boot.java.component.oss.impl.S3OssStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 工厂按 provider 路由到实现类；SDK 客户端构造不发请求，用例离线。
 */
class OssStorageFactoryTest {

    @Test
    void s3RoutesToS3Implementation() throws Exception {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("s3");
        properties.getS3().setRegion("us-east-1");
        properties.getS3().setAccessKeyId("ak");
        properties.getS3().setSecretAccessKey("sk");

        try (OssStorage storage = OssStorageFactory.create(properties)) {
            assertInstanceOf(S3OssStorage.class, storage);
        }
    }

    /** rustfs 复用 S3 实现，且 region 可不配（与 s3 的必填校验不同） */
    @Test
    void rustfsRoutesToS3ImplementationWithoutRegion() throws Exception {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("rustfs");
        properties.getRustfs().setEndpoint("http://127.0.0.1:9000");
        properties.getRustfs().setAccessKeyId("rustfsadmin");
        properties.getRustfs().setSecretAccessKey("rustfsadmin");

        try (OssStorage storage = OssStorageFactory.create(properties)) {
            assertInstanceOf(S3OssStorage.class, storage);
        }
    }

}
