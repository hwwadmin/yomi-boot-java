package com.yomi.boot.java.qucik.starter.testhttplog;

import com.yomi.boot.java.component.desensitize.DesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主自定义脱敏策略演示：工号保留前 4 后 2（策略名 EMPLOYEE_NO）
 * <p>httplog 词表（{@code yomi.httplog.desensitize.key-types}）用策略名引用即可，组件零改动。
 *
 * @author zwei
 */
@Configuration(proxyBeanMethods = false)
public class TestHttpLogStrategyConfig {

    @Bean
    public DesensitizeStrategy employeeNoStrategy() {
        return new KeepEdgeDesensitizeStrategy("EMPLOYEE_NO", 4, 2);
    }

}
