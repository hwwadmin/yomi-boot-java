package com.yomi.boot.java.core.bc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 密码加密接口配置
 *
 * @author zwei
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

}
