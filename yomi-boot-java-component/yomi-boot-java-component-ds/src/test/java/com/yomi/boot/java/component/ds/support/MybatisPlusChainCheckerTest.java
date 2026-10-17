package com.yomi.boot.java.component.ds.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链自检形态：
 * ① 容器里的链 Bean 缺分页时拦启动（MP 默认链、宿主自管链、一个链都没有都算）；
 * ② 每个 {@code SqlSessionFactory}（含业务自建的附加库工厂）的链里都要有分页 + 防全表写，缺失拦启动；
 * ③ 主库工厂缺 Instant 处理器/方言标识同样拦启动（主库由本组件装配）；附加库工厂缺这两项只 WARN；
 * ④ 未挂跨库守卫只 WARN（诊断能力，不改变 SQL 语义）。
 */
class MybatisPlusChainCheckerTest {

    @Test
    void passesWhenPaginationInPlace() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                "mybatisPlusInterceptor", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor()))));

        assertDoesNotThrow(checker::afterSingletonsInstantiated);
    }

    @Test
    void failsFastWhenMpDefaultChainStoleTheBean() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                MybatisPlusChainChecker.MP_DEFAULT_BEAN_NAME, chain(new BlockAttackInnerInterceptor()))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains(MybatisPlusChainChecker.MP_DEFAULT_BEAN_NAME), ex.getMessage());
        assertTrue(ex.getMessage().contains("定义自己的 MybatisPlusInterceptor Bean"), "要给出一条出路: " + ex.getMessage());
    }

    @Test
    void failsFastWhenHostManagedChainMissesPagination() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                "hostChain", chain(new BlockAttackInnerInterceptor()))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("PaginationInnerInterceptor"), "要给出一条出路: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("按 Page 取批的语句"), "要说清为什么必须补: " + ex.getMessage());
    }

    /** 宿主自管链但补上了分页：放行 */
    @Test
    void passesWhenHostManagedChainHasPagination() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                "hostChain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor()))));

        assertDoesNotThrow(checker::afterSingletonsInstantiated);
    }

    @Test
    void failsFastWhenNoInterceptorBeanAtAll() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("mybatis-plus-spring-boot4-starter"), ex.getMessage());
    }

    /** 每个工厂都要带分页：完整的主库工厂放行 */
    @Test
    void passesWhenEveryFactoryCarriesPagination() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(
                context(Map.of("chain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor())),
                        Map.of(DsMaster.SQL_SESSION_FACTORY_BEAN,
                                factory(true, true, true, "mysql"))));

        assertDoesNotThrow(checker::afterSingletonsInstantiated);
    }

    /** 业务自建的附加库工厂缺分页：启动即拦下并点名（缺分页的分页查询会退化成全量取数） */
    @Test
    void failsFastWhenAdditionalLibraryFactoryMissesPagination() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(
                context(Map.of("chain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor())),
                        Map.of("reportSqlSessionFactory", factory(false, true, true, null, false))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("reportSqlSessionFactory"), "要点名是哪个工厂: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("多数据源使用规范"), "要给出模板指引: " + ex.getMessage());
    }

    /** 主库工厂缺 Instant 处理器：主库由本组件装配，缺了就是装配 bug → 拦启动 */
    @Test
    void failsFastWhenMasterFactoryMissesInstantHandler() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(
                context(Map.of("chain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor())),
                        Map.of(DsMaster.SQL_SESSION_FACTORY_BEAN, factory(true, true, false, "mysql"))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains(DsMaster.SQL_SESSION_FACTORY_BEAN), ex.getMessage());
        assertTrue(ex.getMessage().contains("InstantJdbcTypeHandler"), ex.getMessage());
    }

    /** 附加库工厂缺 Instant 处理器或未解析出方言：只 WARN（业务工厂的装配口径 / 库连不上属受支持形态） */
    @Test
    void warnsWhenAdditionalFactoryMissesInstantHandlerAndDialect() {
        String warnings = warningsOf(new MybatisPlusChainChecker(
                context(Map.of("chain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor())),
                        Map.of("reportSqlSessionFactory", factory(true, true, false, null, false)))));
        assertTrue(warnings.contains("reportSqlSessionFactory"), "要点名工厂: " + warnings);
        assertTrue(warnings.contains("DatabaseIdProvider"), "要给出模板指引: " + warnings);
        assertTrue(warnings.contains("databaseId"), "方言缺失也要提示: " + warnings);
    }

    /** 主库工厂未解析出方言（如启动期库连不上）：只 WARN，不拦启动 */
    @Test
    void warnsWhenMasterFactoryHasNoDialect() {
        String warnings = warningsOf(new MybatisPlusChainChecker(
                context(Map.of("chain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor())),
                        Map.of(DsMaster.SQL_SESSION_FACTORY_BEAN, factory(true, true, true, null)))));
        assertTrue(warnings.contains(DsMaster.SQL_SESSION_FACTORY_BEAN), warnings);
        assertTrue(warnings.contains("databaseId"), warnings);
    }

    /** 收集自检的 WARN 日志 */
    private static String warningsOf(MybatisPlusChainChecker checker) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(MybatisPlusChainChecker.class);
        logger.addAppender(appender);
        try {
            checker.afterSingletonsInstantiated();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(event -> event.getLevel().toString().equals("WARN"))
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static MybatisPlusInterceptor chain(InnerInterceptor... inners) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        for (InnerInterceptor inner : inners) {
            interceptor.addInnerInterceptor(inner);
        }
        return interceptor;
    }

    /** 主库工厂形态：链（可选 + 守卫）+ Instant 处理器 + 方言 */
    private static SqlSessionFactory factory(boolean withPagination, boolean withBlockAttack,
                                             boolean withInstant, String databaseId) {
        return factory(withPagination, withBlockAttack, withInstant, databaseId, true);
    }

    private static SqlSessionFactory factory(boolean withPagination, boolean withBlockAttack,
                                             boolean withInstant, String databaseId, boolean withGuard) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MybatisPlusInterceptor chain = new MybatisPlusInterceptor();
        if (withPagination) {
            chain.addInnerInterceptor(new PaginationInnerInterceptor());
        }
        if (withBlockAttack) {
            chain.addInnerInterceptor(new BlockAttackInnerInterceptor());
        }
        configuration.addInterceptor(chain);
        if (withGuard) {
            configuration.addInterceptor(DsCrossDbGuard.of("master", stubDataSource()));
        }
        if (withInstant) {
            configuration.getTypeHandlerRegistry().register(Instant.class, new InstantJdbcTypeHandler());
        }
        if (databaseId != null) {
            configuration.setDatabaseId(databaseId);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 只用于构造守卫的 DataSource 桩（本用例不触发跨库判定） */
    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(MybatisPlusChainCheckerTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> null);
    }

    /** 按请求类型回答 getBeansOfType 的上下文桩（自检只用到这一个能力） */
    private static ApplicationContext context(Map<String, MybatisPlusInterceptor> chains) {
        return context(chains, Map.of());
    }

    private static ApplicationContext context(Map<String, MybatisPlusInterceptor> chains,
                                              Map<String, SqlSessionFactory> factories) {
        return (ApplicationContext) Proxy.newProxyInstance(MybatisPlusChainCheckerTest.class.getClassLoader(),
                new Class<?>[]{ApplicationContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBeansOfType" -> isFactoryQuery(args) ? factories : chains;
                    case "toString" -> "stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> false;
                    default -> null;
                });
    }

    private static boolean isFactoryQuery(Object[] args) {
        return args != null && args.length > 0 && args[0] == SqlSessionFactory.class;
    }

}
