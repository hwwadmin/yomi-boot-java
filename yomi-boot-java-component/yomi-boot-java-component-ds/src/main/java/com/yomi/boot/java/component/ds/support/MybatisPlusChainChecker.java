package com.yomi.boot.java.component.ds.support;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.context.ApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动期自检：容器里**每一个** {@code SqlSessionFactory} 都必须带分页 + 防全表写插件
 * <p>
 * 分页随 ds 组件强制生效（无开关）：组件里按 MP {@code Page} 取批的语句靠拦截器加 LIMIT，缺拦截器会静默退化成
 * 无上限全量取数——所以缺失**一律阻止启动**，宿主自管链、业务自建的附加库工厂都不例外。
 * {@link DsAutoConfiguration} 通过 {@code @AutoConfigureBefore} 优先注册本组件的拦截器链，本类兜底发现
 * 装配顺序竞争或自定义链导致的缺页（MP 的默认链抢占也会被点名）。
 * <p>
 * 主库工厂另外校验 {@code InstantJdbcTypeHandler} 与 {@code databaseId} 是否就位（主库由本组件装配，
 * 缺了就是装配 bug）；业务自建的附加库工厂缺这两项只 WARN（那是业务工厂的装配口径，给出模板指引）。
 * 跨库守卫（{@link DsCrossDbGuard}）缺失同样只 WARN：它是诊断能力，不会改变 SQL 语义；
 * 另外拦下"只写一半 ref"的附加库绑定（详见 {@link #checkMapperRefs}）。
 *
 * @author zwei
 */
@Slf4j
public class MybatisPlusChainChecker implements SmartInitializingSingleton {

    /** MP 默认拦截器链的 Bean 名（即其 {@code @Bean} 方法名）；命中说明本组件在装配竞争里让位了 */
    static final String MP_DEFAULT_BEAN_NAME = "defaultMybatisPlusInterceptor";

    /** 用于取回拦截器链与工厂/判断 Bean 名 */
    private final ApplicationContext context;

    public MybatisPlusChainChecker(ApplicationContext context) {
        this.context = context;
    }

    /** 单例就绪后校验：链 Bean 在位 + 每个工厂的链里都有分页与防全表 + Mapper 绑定没有"只写一半 ref" */
    @Override
    public void afterSingletonsInstantiated() {
        checkChainBean();
        List<String> problems = new ArrayList<>();
        if (context.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory beanFactory) {
            checkMapperRefs(beanFactory, problems);
        }
        Map<String, SqlSessionFactory> factories = context.getBeansOfType(SqlSessionFactory.class);
        factories.forEach((beanName, factory) -> checkFactory(beanName, factory, problems));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("MyBatis-Plus 拦截器链自检失败：\n- " + String.join("\n- ", problems));
        }
    }

    /**
     * 只写一半 ref 的附加库绑定：{@link MapperFactoryBean} 的 {@code sqlSessionFactory} 与
     * {@code sqlSessionTemplate} 两个属性都会被按类型自动装配，缺的那半会拿到容器里的**主库** Bean，
     * 把该 Mapper 静默劫持到主库（已显式写的那半反过来被覆盖）。
     */
    private void checkMapperRefs(ConfigurableListableBeanFactory beanFactory, List<String> problems) {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            if (!MapperFactoryBean.class.getName().equals(definition.getBeanClassName())) {
                continue;
            }
            PropertyValues values = definition.getPropertyValues();
            boolean factoryRef = values.contains("sqlSessionFactory");
            boolean templateRef = values.contains("sqlSessionTemplate");
            if (factoryRef == templateRef || refersToMaster(values, factoryRef)) {
                // 都写 = 显式绑定；都不写 = 走主库（@Primary 兜底）；指向主库时写一半无害（容器里的模板就是主库的）
                continue;
            }
            problems.add("Mapper 绑定 [" + beanName + "] 只写了 "
                    + (factoryRef ? "sqlSessionFactoryRef" : "sqlSessionTemplateRef") + "，缺 "
                    + (factoryRef ? "sqlSessionTemplateRef" : "sqlSessionFactoryRef")
                    + "：MapperFactoryBean 的两个属性都会被按类型装配，缺的那半会注入容器里的主库 Bean，"
                    + "把这个 Mapper 静默劫持到主库去执行。附加库绑定请把两个 ref 一起写上"
                    + "（见《组件使用说明》『多数据源使用规范』）");
        }
    }

    /** 该 Mapper 唯一写了的那个 ref 是否指向主库那套 Bean（指向主库时写一半无害） */
    private static boolean refersToMaster(PropertyValues values, boolean factoryRef) {
        Object ref = values.getPropertyValue(factoryRef ? "sqlSessionFactory" : "sqlSessionTemplate");
        if (!(ref instanceof RuntimeBeanReference reference)) {
            return false;
        }
        String masterBeanName = factoryRef ? DsMaster.SQL_SESSION_FACTORY_BEAN : DsMaster.SQL_SESSION_TEMPLATE_BEAN;
        return masterBeanName.equals(reference.getBeanName());
    }

    /** 容器里的链 Bean 必须带分页：本组件接管过装配顺序，这里兜底竞争与宿主自定义链 */
    private void checkChainBean() {
        Map<String, MybatisPlusInterceptor> chains = context.getBeansOfType(MybatisPlusInterceptor.class);
        if (chains.isEmpty()) {
            throw new IllegalStateException("容器里没有 MybatisPlusInterceptor：请确认引入 mybatis-plus-spring-boot4-starter；"
                    + "ds 组件的分页与『防全表更新/删除』随组件强制生效（无开关），需要自行管理请定义自己的 "
                    + "MybatisPlusInterceptor Bean");
        }
        if (chains.size() > 1) {
            log.warn("容器里存在多个 MybatisPlusInterceptor（{}）：请确认注入点用的是预期那一个", chains.keySet());
        }
        if (chains.values().stream().flatMap(chain -> chain.getInterceptors().stream())
                .anyMatch(PaginationInnerInterceptor.class::isInstance)) {
            log.info("MyBatis-Plus 拦截器链自检通过：分页插件在位（链: {}）", describe(chains));
            return;
        }
        if (chains.containsKey(MP_DEFAULT_BEAN_NAME)) {
            throw new IllegalStateException("MyBatis-Plus 拦截器链里没有分页插件：MP 自带的 " + MP_DEFAULT_BEAN_NAME
                    + " 抢在了本组件前面（自动装配顺序竞争，宿主声明 InnerInterceptor 时触发），"
                    + "本组件的分页与『防全表更新/删除』会整体失效；组件里按 Page 取批的语句还会退化成无上限全量取数。出路："
                    + "① 不要自己定义 MybatisPlusInterceptor，让本组件接管（已声明 @AutoConfigureBefore 抢占）"
                    + "② 自行管理：定义自己的 MybatisPlusInterceptor Bean 并自行补上分页与防全表写。当前链: " + describe(chains));
        }
        // 宿主自管链同样强制：分页是硬依赖（按 Page 取批的语句靠它加 LIMIT），不再只告警
        throw new IllegalStateException("MyBatis-Plus 拦截器链里没有分页插件（宿主自定义了 MybatisPlusInterceptor）："
                + "分页随 ds 组件强制生效（组件里按 Page 取批的语句依赖它加 LIMIT，缺了会无上限全量取数）。出路："
                + "① 不要自己定义 MybatisPlusInterceptor，让本组件接管（含分页 + 防全表写）"
                + "② 自行管理：在自己的 MybatisPlusInterceptor 里补上 PaginationInnerInterceptor（与防全表写）。"
                + "当前链: " + describe(chains));
    }

    /** 单个工厂：分页/防全表缺失即记问题；主库另有 Instant/方言硬校验，附加库与守卫缺失只告警 */
    private void checkFactory(String beanName, SqlSessionFactory factory, List<String> problems) {
        List<Interceptor> interceptors = factory.getConfiguration().getInterceptors();
        List<InnerInterceptor> inner = interceptors.stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .flatMap(chain -> chain.getInterceptors().stream())
                .toList();
        String plugins = interceptors.stream().map(it -> it.getClass().getSimpleName())
                .collect(Collectors.joining(", ", "[", "]"));
        if (inner.stream().noneMatch(PaginationInnerInterceptor.class::isInstance)) {
            problems.add("工厂 " + beanName + " 的链里没有分页插件（链: " + plugins + "）："
                    + "按 Page 取批的语句会退化成无上限全量取数。业务自建附加库工厂请按《组件使用说明》"
                    + "『多数据源使用规范』的模板，把容器的 MybatisPlusInterceptor Bean 设进工厂插件");
        }
        if (inner.stream().noneMatch(BlockAttackInnerInterceptor.class::isInstance)) {
            problems.add("工厂 " + beanName + " 的链里没有防全表更新/删除插件（链: " + plugins + "）："
                    + "一条漏写 WHERE 的 update/delete 就能清空整表");
        }
        boolean masterFactory = DsMaster.SQL_SESSION_FACTORY_BEAN.equals(beanName);
        if (!factory.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(Instant.class)
                || !(factory.getConfiguration().getTypeHandlerRegistry().getTypeHandler(Instant.class)
                instanceof InstantJdbcTypeHandler)) {
            report(beanName, masterFactory, problems, "缺少 Instant 写库处理器 InstantJdbcTypeHandler"
                    + "（PG 驱动不接受 setObject(Instant)）");
        }
        if (factory.getConfiguration().getDatabaseId() == null) {
            // 连不上库是受支持形态（此时方言语句不注册属预期）；连上却不认识方言时 DsDatabaseIdProvider 已 fail-fast
            log.warn("[ds] 工厂 {} 未解析出数据库方言（databaseId）：XML 里的 databaseId 方言语句不会注册。"
                    + "库连不上时属预期（启动期不探库）；若库正常，请检查 DsDatabaseIdProvider 是否装配", beanName);
        }
        if (interceptors.stream().noneMatch(DsCrossDbGuard.class::isInstance)) {
            log.warn("[ds] 工厂 {} 未挂跨库访问守卫（{}）：一个事务里访问别的库不会被点出来。"
                            + "业务自建附加库工厂可在模板里加一行 factory.setPlugins(chain, DsCrossDbGuard.of(库名, dataSource))",
                    beanName, DsCrossDbGuard.class.getSimpleName());
        }
    }

    /** 主库工厂缺项 → 记问题（fail-fast）；附加库工厂缺项 → 只 WARN */
    private static void report(String beanName, boolean masterFactory, List<String> problems, String detail) {
        if (masterFactory) {
            problems.add("主库工厂 " + beanName + " " + detail + "（主库由 component-ds 装配，缺了就是装配 bug）");
        } else {
            log.warn("[ds] 附加库工厂 {} {}：请检查其装配是否按《组件使用说明》的模板复用了容器里的 "
                    + "TypeHandler Bean 与 DatabaseIdProvider", beanName, detail);
        }
    }

    /** 打印各链的类名列表，便于定位是谁占位 */
    private static String describe(Map<String, MybatisPlusInterceptor> chains) {
        return chains.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().getInterceptors().stream()
                        .map(inner -> inner.getClass().getSimpleName())
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining("; "));
    }

}
