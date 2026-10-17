package com.yomi.boot.java.component.httplog.support;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * httplog 后台任务命名：模块前缀 + 用途驼峰 + {@code _服务名}（分隔符统一下划线）
 *
 * @author zwei
 */
class HttpLogTaskNamingTest {

    @Test
    void retentionTaskName_usesUnderscoreServiceSuffix() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setServiceName("demo");
        HttpLogKeys keys = new HttpLogKeys(properties, null);
        assertEquals("httpLogRetentionCleaner_demo", keys.retentionTaskName());
    }

}
