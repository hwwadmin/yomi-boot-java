package com.yomi.boot.java.component.ds.support;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动期强制校验 {@link MybatisPlusInterceptor} 链里有分页插件：缺了**一律阻止启动**（宿主自管链也不例外）。
 * <p>
 * 分页随 ds 组件强制生效（无开关）：组件里按 MP {@code Page} 取批的语句靠拦截器加 LIMIT，缺拦截器会静默退化成
 * 无上限全量取数；{@link com.yomi.boot.java.component.ds.config.DsAutoConfiguration} 通过
 * {@code @AutoConfigureBefore} 优先注册本组件的拦截器链，本类兜底发现装配顺序竞争或自定义链导致的缺页。
 *
 * @author zwei
 */
@Slf4j
public class MybatisPlusChainChecker implements SmartInitializingSingleton {

    /** MP 默认拦截器链的 Bean 名（即其 {@code @Bean} 方法名）；命中说明本组件在装配竞争里让位了 */
    static final String MP_DEFAULT_BEAN_NAME = "defaultMybatisPlusInterceptor";

    /** 用于取回拦截器链与判断 Bean 名 */
    private final ApplicationContext context;

    public MybatisPlusChainChecker(ApplicationContext context) {
        this.context = context;
    }

    /** 单例就绪后校验分页插件是否在拦截器链中 */
    @Override
    public void afterSingletonsInstantiated() {
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

    /** 打印各链的类名列表，便于定位是谁占位 */
    private static String describe(Map<String, MybatisPlusInterceptor> chains) {
        return chains.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().getInterceptors().stream()
                        .map(inner -> inner.getClass().getSimpleName())
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining("; "));
    }

}
