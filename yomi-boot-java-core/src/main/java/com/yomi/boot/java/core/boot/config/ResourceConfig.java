package com.yomi.boot.java.core.boot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射默认配置
 *
 * @author zwei
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class ResourceConfig implements WebMvcConfigurer {

    /**
     * 前端静态资源目录配置
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/");
        registry.addResourceHandler("/public/**").addResourceLocations("classpath:/public/");
        registry.addResourceHandler("/views/**").addResourceLocations("classpath:/views/");
        log.info("静态资源映射生效: /static/** /public/** /views/**");
    }

}
