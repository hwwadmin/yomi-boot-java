package com.yomi.boot.java.component.ds.support;

import org.springframework.beans.factory.ListableBeanFactory;

import javax.sql.DataSource;
import java.util.List;

/**
 * 主库（master）的固定命名与解析规则
 * <p>
 * ds 只装配主数据源：数据源、工厂、模板、事务管理器四个 Bean 用 {@code masterXxx} 命名并全部 {@code @Primary}，
 * 所有不指定名称的按类型注入（业务与组件的 {@code @MapperScan}、{@code @Transactional}、
 * {@code TransactionTemplate}）都落在主库上。附加库完全由业务系统自行定义与装配（用
 * {@code @MapperScan(sqlSessionFactoryRef = ..., sqlSessionTemplateRef = ...)} 显式绑定），本组件不参与。
 * <p>
 * 解析顺序：① ds 声明的 {@code masterXxx}（宿主要用自己的实现就声明同名 Bean）；② 容器里唯一或
 * {@code @Primary} 的那个；③ 多候选且无 {@code @Primary} → 启动失败并列出候选。
 *
 * @author zwei
 */
public final class DsMaster {

    /** 主库名（日志展示用；也用作 Hikari 默认池名） */
    public static final String LIBRARY_NAME = "master";

    /** 主数据源 Bean 名 */
    public static final String DATASOURCE_BEAN = "masterDataSource";

    /** 主库 SqlSessionFactory Bean 名 */
    public static final String SQL_SESSION_FACTORY_BEAN = "masterSqlSessionFactory";

    /** 主库 SqlSessionTemplate Bean 名 */
    public static final String SQL_SESSION_TEMPLATE_BEAN = "masterSqlSessionTemplate";

    /** 主库事务管理器 Bean 名 */
    public static final String TRANSACTION_MANAGER_BEAN = "masterTransactionManager";

    private DsMaster() {
    }

    /** 解析主数据源：{@code masterDataSource} → 唯一/{@code @Primary} → 失败 */
    public static Resolved<DataSource> resolveDataSource(ListableBeanFactory beanFactory) {
        return resolve(beanFactory, DataSource.class, DATASOURCE_BEAN, "主数据源");
    }

    /** 通用解析：先按名字命中，再退到"唯一或 @Primary"，都没有则启动失败并列出候选 */
    public static <T> Resolved<T> resolve(ListableBeanFactory beanFactory, Class<T> type,
                                          String masterBeanName, String description) {
        List<String> candidates = List.of(beanFactory.getBeanNamesForType(type, false, false));
        if (candidates.contains(masterBeanName)) {
            return new Resolved<>(masterBeanName, beanFactory.getBean(masterBeanName, type));
        }
        T unique = beanFactory.getBeanProvider(type).getIfUnique();
        if (unique != null) {
            return new Resolved<>("", unique);
        }
        throw new IllegalStateException("[ds] 无法确定" + description + "：容器里的 " + type.getSimpleName()
                + " 多候选且无 @Primary（候选: " + candidates + "）。请给它加 @Primary，或命名为 " + masterBeanName
                + "；附加库的" + description + "请用 @MapperScan(sqlSessionFactoryRef = ...) 显式绑定");
    }

    /** 解析结果：命中的 Bean 名（按"唯一或 @Primary"命中时为空串）+ 实例 */
    public record Resolved<T>(String beanName, T bean) {
    }

}
