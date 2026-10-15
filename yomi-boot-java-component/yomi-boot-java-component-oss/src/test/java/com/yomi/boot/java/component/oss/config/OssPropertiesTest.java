package com.yomi.boot.java.component.oss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link OssProperties#validate()} 行为 */
class OssPropertiesTest {

    @Test
    void disabledSkipsValidation() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(false);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void minioProviderIsRejected() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("minio");
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("不支持"), ex.getMessage());
    }

    @Test
    void s3RequiresCredentials() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("s3");
        properties.getS3().setRegion("us-east-1");
        properties.getS3().setAccessKeyId("ak");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rustfsRequiresCredentials() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("rustfs");
        properties.getRustfs().setEndpoint("http://127.0.0.1:9000");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rustfsValidatesWithoutRegion() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("rustfs");
        properties.getRustfs().setEndpoint("http://127.0.0.1:9000");
        properties.getRustfs().setAccessKeyId("rustfsadmin");
        properties.getRustfs().setSecretAccessKey("rustfsadmin");
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void aliossValidates() {
        OssProperties properties = new OssProperties();
        properties.setEnabled(true);
        properties.setProvider("alioss");
        properties.getAlioss().setRegion("cn-hangzhou");
        properties.getAlioss().setAccessKeyId("ak");
        properties.getAlioss().setSecretAccessKey("sk");
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void toStringNeverExposesSecret() {
        OssProperties properties = new OssProperties();
        properties.getS3().setAccessKeyId("ak-s3");
        properties.getS3().setSecretAccessKey("sk-s3-secret");
        properties.getRustfs().setAccessKeyId("ak-rustfs");
        properties.getRustfs().setSecretAccessKey("sk-rustfs-secret");
        properties.getAlioss().setAccessKeyId("ak-ali");
        properties.getAlioss().setSecretAccessKey("sk-ali-secret");

        String text = properties.toString() + properties.getS3() + properties.getRustfs() + properties.getAlioss();

        assertFalse(text.contains("sk-s3-secret"), "S3 密钥不得出现在 toString");
        assertFalse(text.contains("sk-rustfs-secret"), "RustFS 密钥不得出现在 toString");
        assertFalse(text.contains("sk-ali-secret"), "AliOSS 密钥不得出现在 toString");
        assertTrue(text.contains("ak-s3"), "accessKeyId 非敏感、保留用于排查");
        assertTrue(text.contains("ak-rustfs"), "accessKeyId 非敏感、保留用于排查");
    }

}
