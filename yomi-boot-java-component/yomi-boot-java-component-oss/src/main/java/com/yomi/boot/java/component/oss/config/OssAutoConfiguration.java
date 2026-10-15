package com.yomi.boot.java.component.oss.config;

import com.yomi.boot.java.component.oss.OssStorage;
import com.yomi.boot.java.component.oss.factory.OssStorageFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 对象存储自动装配。
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 注册。
 * 仅 {@code yomi.oss.enabled=true} 时创建 {@link OssStorage}；日志不输出密钥。
 * {@link OssStorage} 继承 {@link java.lang.AutoCloseable}，本 Bean 使用 {@code destroyMethod = close}。
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(OssProperties.class)
@ConditionalOnProperty(value = "yomi.oss.enabled", havingValue = "true")
public class OssAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OssStorage ossStorage(OssProperties properties) {
        OssStorage storage = OssStorageFactory.create(properties);
        log.info("对象存储已启用: provider={}, publicBaseUrl={}",
                properties.resolvedProvider().configValue(),
                properties.getPublicBaseUrl());
        return storage;
    }

}
