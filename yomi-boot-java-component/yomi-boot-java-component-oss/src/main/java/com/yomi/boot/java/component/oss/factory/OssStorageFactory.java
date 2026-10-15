package com.yomi.boot.java.component.oss.factory;

import com.yomi.boot.java.component.oss.OssStorage;
import com.yomi.boot.java.component.oss.config.OssProperties;
import com.yomi.boot.java.component.oss.impl.AliOssStorage;
import com.yomi.boot.java.component.oss.impl.S3OssStorage;

/**
 * 按配置创建 {@link OssStorage}，供自动装配与单测使用。
 *
 * @author zwei
 */
public final class OssStorageFactory {

    private OssStorageFactory() {
    }

    /** 先 {@link OssProperties#validate()}，再按 provider 实例化对应实现 */
    public static OssStorage create(OssProperties properties) {
        properties.validate();
        return switch (properties.resolvedProvider()) {
            case S3 -> new S3OssStorage(properties);
            case RUSTFS -> S3OssStorage.forRustfs(properties);
            case ALIOSS -> new AliOssStorage(properties);
        };
    }

}
