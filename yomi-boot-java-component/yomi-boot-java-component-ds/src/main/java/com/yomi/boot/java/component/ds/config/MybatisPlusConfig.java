package com.yomi.boot.java.component.ds.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * MyBatis-Plus配置
 * <p>
 * 随 ds 组件强制生效（无开关）：装配分页与防全表更新/删除，并收集宿主提供的 {@link InnerInterceptor}。
 * 若容器已存在 {@link MybatisPlusInterceptor} Bean，则本配置整体让位，分页与防全表写由宿主自行负责。
 * <p>
 * 拦截器顺序：分页（改写 SQL）在前，宿主自定义拦截器在后。
 * <p>
 * 分页方言默认<b>按当前连接自动探测</b>（多数据源/多方言各自正确）；确定性优先时可用
 * {@code yomi.ds.pagination.db-type}（如 {@code POSTGRE_SQL}）显式指定，非法值启动期报错
 *
 * @author zwei
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            @Value("${yomi.ds.pagination.db-type:}") String dbType,
            ObjectProvider<InnerInterceptor> hostInterceptorsProvider) {
        var interceptor = new MybatisPlusInterceptor();

        DbType resolved = resolveDbType(dbType);
        if (resolved == null) {
            log.info("分页方言: 自动探测（按当前连接的 JDBC URL 推导；如需固定可用 yomi.ds.pagination.db-type 指定）");
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        } else {
            log.info("分页方言: {}（由 yomi.ds.pagination.db-type 指定）", resolved);
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(resolved));
        }

        log.info("MyBatis-Plus 拦截器: 已装配 {}（拦截无有效条件的全表 UPDATE/DELETE；"
                + "确需全表操作时可在 Mapper 方法上标 @InterceptorIgnore(blockAttack = \"true\") 豁免）",
                BlockAttackInnerInterceptor.class.getSimpleName());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        hostInterceptorsProvider.orderedStream().forEach(hostInterceptor -> {
            if (containsSameClass(interceptor, hostInterceptor)) {
                // 同类已内置（分页/防全表）：再挂一份会重复解析与执行；需要自定义请定义自己的 MybatisPlusInterceptor
                log.warn("MyBatis-Plus 拦截器: 宿主的 {} 与内置同类，已跳过；如需自定义该插件请自行定义 MybatisPlusInterceptor Bean",
                        hostInterceptor.getClass().getName());
                return;
            }
            interceptor.addInnerInterceptor(hostInterceptor);
            log.info("MyBatis-Plus 拦截器: 已装配宿主提供的 {}", hostInterceptor.getClass().getName());
        });
        return interceptor;
    }

    /** 判断拦截器链是否已包含同类插件 */
    private static boolean containsSameClass(MybatisPlusInterceptor interceptor, InnerInterceptor candidate) {
        return interceptor.getInterceptors().stream()
                .anyMatch(existing -> existing.getClass().equals(candidate.getClass()));
    }

    /** 空表示按 JDBC 连接自动探测方言；非法值在启动期报错 */
    private static DbType resolveDbType(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return DbType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("yomi.ds.pagination.db-type 取值非法: [" + raw
                    + "]，合法取值见 com.baomidou.mybatisplus.annotation.DbType（如 MYSQL、POSTGRE_SQL、ORACLE）", e);
        }
    }

}
