package com.acme.dshost;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配顺序：宿主不扫描 {@code com.yomi}（主类在 com.acme）且声明了 {@code InnerInterceptor} 时，
 * 容器里的链由 ds 组件提供，含分页 + 防全表写，不使用 MyBatis-Plus 的默认链。
 * <p>用真实 {@link SpringApplication} 启动：自动配置排序只在真实启动路径上发生，
 * {@code ApplicationContextRunner} 复现不了这一顺序。</p>
 */
class DsInterceptorOrderingTest {

    @SpringBootApplication
    static class HostApp {

        @Bean
        InnerInterceptor hostInterceptor() {
            return new BlockAttackInnerInterceptor();
        }
    }

    @Test
    void dsChainWinsWhenHostDeclaresInnerInterceptor() {
        SpringApplication app = new SpringApplication(HostApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // 无数据源宿主（不接库，只用 ds 的工具类）：排除 Boot 的数据源自动配置（jdbc starter 在场但不接库时，
        // 它自己会因没有 url/driver 而启动失败），主库四件套不装配，拦截器链照常就位
        try (ConfigurableApplicationContext context = app.run(
                "--yomi.ds.preheat.enabled=false",
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")) {
            MybatisPlusInterceptor chain = context.getBean(MybatisPlusInterceptor.class);

            assertTrue(chain.getInterceptors().stream().anyMatch(PaginationInnerInterceptor.class::isInstance),
                    "分页插件必须在链里（宿主声明 InnerInterceptor 时不得让位）: " + chain.getInterceptors());
            assertTrue(chain.getInterceptors().stream().anyMatch(BlockAttackInnerInterceptor.class::isInstance),
                    "防全表更新/删除也必须在链里: " + chain.getInterceptors());
            // 该 Bean 名是 MP 默认链的方法名：它出现即说明生效的是 MP 默认链；MP 若改名，本断言会先红（提醒同步自检里的常量）
            assertFalse(context.containsBean("defaultMybatisPlusInterceptor"),
                    "MP 默认链不应注册（本组件已抢占该 Bean）");
        }
    }
}
