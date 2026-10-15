package com.yomi.boot.java.component.httplog.config;

import com.yomi.boot.java.component.desensitize.config.DesensitizeAutoConfiguration;
import com.yomi.boot.java.component.httplog.collect.HttpLogFilter;
import com.yomi.boot.java.component.httplog.IHttpLogService;
import com.yomi.boot.java.component.httplog.model.HttpLogMode;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.queue.kafka.KafkaHttpLogTransport;
import com.yomi.boot.java.component.httplog.queue.memory.MemoryHttpLogTransport;
import com.yomi.boot.java.component.httplog.queue.redis.RedisHttpLogTransport;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.store.HttpLogStore;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配测试：开关、模式条件装配、启动行为（空词表 WARN 放行 / 未注册策略 fail-fast）；
 * Mapper 由装配类的 {@code @MapperScan} 扫出（宿主无需扫描 {@code com.yomi}）
 *
 * @author zwei
 */
class HttpLogAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HttpLogAutoConfiguration.class,
                    DesensitizeAutoConfiguration.class))
            .withUserConfiguration(StubConfiguration.class);

    @Test
    void disabled_notWired() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpLogAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HttpLogStore.class);
                    assertThat(context).doesNotHaveBean(HttpLogSink.class);
                    assertThat(context).doesNotHaveBean(HttpLogFilter.class);
                });
    }

    @Test
    void memoryMode_wiresCoreBeans() {
        runner.withPropertyValues(baseProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MemoryHttpLogTransport.class);
                    assertThat(context).hasSingleBean(HttpLogSink.class);
                    assertThat(context).hasSingleBean(HttpLogConsumer.class);
                    assertThat(context).hasSingleBean(HttpLogStore.class);
                    assertThat(context).hasSingleBean(IHttpLogRepository.class);
                    assertThat(context).hasSingleBean(IHttpLogService.class);
                    assertThat(context).hasSingleBean(HttpLogLifecycle.class);
                });
    }

    @Test
    void emptyKeyTypes_startsWithWarn() {
        // 空词表 WARN 放行（不 fail-fast）
        runner.withPropertyValues(baseProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HttpLogLifecycle.class);
                });
    }

    @Test
    void unknownStrategyName_failsFast() {
        runner.withPropertyValues(baseProperties())
                .withPropertyValues("yomi.httplog.desensitize.key-types.mobile=NOT_EXISTS")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure()))
                            .contains("NOT_EXISTS")
                            .contains("已注册");
                });
    }

    @Test
    void registeredStrategyName_passes() {
        runner.withPropertyValues(baseProperties())
                .withPropertyValues("yomi.httplog.desensitize.key-types.mobile=MOBILE")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void redisMode_withoutRedis_failsFast() {
        runner.withPropertyValues(baseProperties())
                .withPropertyValues("yomi.httplog.mode=redis")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure())).contains("mode=redis");
                });
    }

    @Test
    void kafkaMode_withoutKafkaTemplate_failsFast() {
        runner.withPropertyValues(baseProperties())
                .withPropertyValues("yomi.httplog.mode=kafka")
                .run(context -> {
                    // bean 创建顺序不定：可能先失败于 KafkaTemplate 或 ConsumerFactory，两者都含 mode=kafka
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure())).contains("mode=kafka");
                });
    }

    @Test
    void memoryMode_doesNotWireNonMemoryTransports() {
        runner.withPropertyValues(baseProperties())
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisHttpLogTransport.class);
                    assertThat(context).doesNotHaveBean(KafkaHttpLogTransport.class);
                });
    }

    @Test
    void modeUppercase_wiresSameAsLowercase() {
        // 锁事实（2026-10-15 实测 + OnPropertyCondition$Spec.isMatch 字节码核实）：havingValue 比较是 equalsIgnoreCase，
        // 大写写法（mode: MEMORY / 环境变量 YOMI_HTTPLOG_MODE=REDIS）照常装配——勿按"条件区分大小写"去改宽松条件
        runner.withPropertyValues(baseProperties())
                .withPropertyValues("yomi.httplog.mode=MEMORY")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MemoryHttpLogTransport.class);
                    assertThat(context.getBean(HttpLogProperties.class).getMode()).isEqualTo(HttpLogMode.MEMORY);
                });
    }

    @Test
    void redisModeUppercase_matchesRedisConfiguration() {
        runner.withPropertyValues(baseProperties())
                .withPropertyValues("yomi.httplog.mode=Redis")
                .run(context -> {
                    // 大写同样命中 redis 条件 → 走依赖校验（未命中时才会报"缺 HttpLogSink Bean"）
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure())).contains("mode=redis");
                });
    }

    @Test
    void modeWithSurroundingBlank_bindsButNoTransportWired() {
        // 记录现状（非期望契约，2026-10-15 H4 复核实测）：绑定层的 lenient 枚举转换会 trim（" redis " → REDIS）能绑定成功，
        // 而 @ConditionalOnProperty 的比较不 trim → 三个传输都不装配、最终报"缺 HttpLogSink Bean"。
        // 属性源只能经 withInitializer 注入：TestPropertyValues 自己会 trim 值（Pair 构造时 trim 键与值），用 withPropertyValues 复现不出来。
        // Boot 全局行为（各组件同款条件写法），要改属全局议题；条件改为 trim 容忍时本用例应一并调整。
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("blank-padded-mode", Map.of("yomi.httplog.mode", " redis "))))
                .withPropertyValues(baseProperties())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure())).contains("HttpLogSink");
                });
    }

    @Test
    void lifecyclePhase_lowerThanWebContainerLifecycles() {
        // Boot 容器生命周期：graceful-shutdown=MAX-1024、server-stop=MAX-2048；phase 更低才能"停机晚于容器停止收请求"
        runner.withPropertyValues(baseProperties())
                .run(context -> assertThat(context.getBean(HttpLogLifecycle.class).getPhase())
                        .isLessThan(Integer.MAX_VALUE - 2048));
    }

    @Test
    void servletApplication_wiresFilterRegistration() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpLogAutoConfiguration.class,
                        DesensitizeAutoConfiguration.class))
                .withUserConfiguration(StubConfiguration.class)
                .withPropertyValues(baseProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HttpLogFilter.class);
                    assertThat(context).hasBean("httpLogFilterRegistration");
                });
    }

    private static String[] baseProperties() {
        return new String[]{
                "yomi.httplog.enabled=true",
                "yomi.httplog.service-name=demo",
                "yomi.httplog.retention.enabled=false"};
    }

    /** 遍历异常链拼接消息（启动失败常被 BeanCreationException 包装） */
    private static String messages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            builder.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return builder.toString();
    }

    /** 替身：SqlSessionFactory 用真实 Configuration + 空 DataSource（装配测试不触库）；Mapper 由装配类自己扫描 */
    @Configuration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        SqlSessionFactory sqlSessionFactory() {
            DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                    HttpLogAutoConfigurationTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> defaultValue(method.getReturnType()));
            Environment environment = new Environment("httplog-test", new ManagedTransactionFactory(), dataSource);
            return new SqlSessionFactoryBuilder()
                    .build(new org.apache.ibatis.session.Configuration(environment));
        }

        private static Object defaultValue(Class<?> type) {
            if (type == int.class || type == Integer.class) {
                return 0;
            }
            if (type == long.class || type == Long.class) {
                return 0L;
            }
            if (type == boolean.class || type == Boolean.class) {
                return false;
            }
            return null;
        }

    }

}
