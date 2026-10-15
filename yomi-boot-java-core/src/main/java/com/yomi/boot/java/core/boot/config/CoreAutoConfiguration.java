package com.yomi.boot.java.core.boot.config;

import com.yomi.boot.java.core.aop.CostTimeAop;
import com.yomi.boot.java.core.bc.PasswordEncoderConfig;
import com.yomi.boot.java.core.boot.cors.CorsConfig;
import com.yomi.boot.java.core.boot.cors.CorsProperties;
import com.yomi.boot.java.core.boot.id.IdProperties;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * core 自动装配：全局异常、Web JSON、CORS、静态资源、BCrypt 密码编码器、XSS/耗时切面、IOC 帮助类与雪花配置。
 * <p>
 * 以自动装配（{@code META-INF/spring/...AutoConfiguration.imports}）注册，宿主无需扫描 {@code com.yomi}；
 * 各实现类按 auto-configuration 推荐方式用 {@link Import} 显式列出，宿主连 {@code com.yomi} 一起扫到也是同一份 Bean 定义。
 * 自动装配阶段晚于宿主 Bean 注册，配置类里的 {@code @ConditionalOnMissingBean} 才能稳定看到宿主的自定义 Bean。
 * <p>
 * 排在 Boot 的 HTTP 转换器自动装配之前：JsonConfig 的转换器 Bean 先就位，Boot 的默认 Jackson 转换器定制器
 * 才会因 {@code @ConditionalOnMissingBean(JacksonJsonHttpMessageConverter.class)} 让位，避免出现两套 JSON 转换器。
 *
 * @author zwei
 */
@AutoConfiguration
@AutoConfigureBefore(name = "org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration")
@EnableConfigurationProperties({IdProperties.class, CorsProperties.class})
@Import({SpringUtils.class, GlobalExceptionAdvice.class, CostTimeAop.class,
        PasswordEncoderConfig.class, JsonConfig.class, CorsConfig.class, ResourceConfig.class})
public class CoreAutoConfiguration {

}
