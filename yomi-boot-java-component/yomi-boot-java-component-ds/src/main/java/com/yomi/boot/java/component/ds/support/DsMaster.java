package com.yomi.boot.java.component.ds.support;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主库（master）的固定命名与解析规则：主库只由 component-ds 装配，业务只能通过 yml 配置主库
 * （{@code spring.datasource.*}），不得声明 {@code masterXxx} 顶替装配流程（声明即启动失败）。
 * <p>
 * 主库四件套（数据源/工厂/模板/事务管理器）全部 {@code @Primary}：不指定名称的按类型注入
 * （{@code @MapperScan} 不写 ref、{@code @Transactional}、{@code TransactionTemplate}）都落在主库上。
 * 组件侧一切"要用主库"的解析都走本类（按名字，不猜）：名字不在即失败并点名候选与出路；
 * 附加库由业务系统自行定义与装配（自己的 Bean 名 + {@code @MapperScan} 两个 ref + 显式
 * {@code transactionManager}），且不要给附加库那套标 {@code @Primary}。
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

    /** 应用的事务模板 Bean 名（Boot 标准名；候选里优先它） */
    static final String TRANSACTION_TEMPLATE_BEAN = "transactionTemplate";

    private DsMaster() {
    }

    /** 主库四件套的 Bean 名（业务不得占用，见 {@link DsMasterExclusiveCondition}） */
    public static List<String> beanNames() {
        return List.of(DATASOURCE_BEAN, SQL_SESSION_FACTORY_BEAN, SQL_SESSION_TEMPLATE_BEAN, TRANSACTION_MANAGER_BEAN);
    }

    /** 主数据源：只认 {@code masterDataSource}（组件数据访问、跨库诊断都锚在它上面） */
    public static DataSource requireDataSource(ListableBeanFactory beanFactory) {
        return require(beanFactory, DataSource.class, DATASOURCE_BEAN, "主数据源");
    }

    /** 主库事务管理器：{@code @Transactional} 不写 qualifier 时按类型解析到的那一个 */
    public static PlatformTransactionManager requireTransactionManager(ListableBeanFactory beanFactory) {
        return require(beanFactory, PlatformTransactionManager.class, TRANSACTION_MANAGER_BEAN, "主库事务管理器");
    }

    /**
     * 取主库设施：只认本组件装配的固定名字，缺失即失败并列出候选与出路
     * <p>
     * 不做"唯一/{@code @Primary} 兜底"：那正是"业务自带库顶主库"的形态，已被明令禁止——
     * 主库要在 yml 里配 {@code spring.datasource.url}。
     */
    public static <T> T require(ListableBeanFactory beanFactory, Class<T> type, String masterBeanName, String description) {
        if (beanFactory.containsBean(masterBeanName)) {
            return beanFactory.getBean(masterBeanName, type);
        }
        throw new IllegalStateException("[ds] 未装配" + description + "（Bean " + masterBeanName + "）："
                + "主数据源由 component-ds 独占装配，请在 yml 配置 spring.datasource.url（业务不得声明 masterXxx 顶替主库）；"
                + "当前容器里的 " + type.getSimpleName() + " 候选: " + candidates(beanFactory, type)
                + "（业务附加库请用自己的 Bean 名，不要占用主库名）");
    }

    /**
     * 取应用的事务模板：必须绑定主库事务管理器（唯一性不等于正确性）
     * <p>
     * 宿主为别的库声明 TransactionTemplate 时，Boot 的标准模板会因
     * {@code @ConditionalOnMissingBean(TransactionOperations)} 退让，容器里就只剩"绑别的库"的那一个——
     * 那样组件会写在一个不属于业务事务的连接上。所以按"绑定关系"选：
     * 候选里优先 {@code @Primary}，其次 Boot 标准名 {@code transactionTemplate}，只有一个就直接用；
     * 一个绑主库的都没有、或多个无法判定，都启动失败并点名候选与出路。
     */
    public static TransactionTemplate requireTransactionTemplate(ListableBeanFactory beanFactory) {
        PlatformTransactionManager master = requireTransactionManager(beanFactory);
        Map<String, TransactionTemplate> all = new LinkedHashMap<>(beanFactory.getBeansOfType(TransactionTemplate.class));
        if (all.isEmpty()) {
            throw new IllegalStateException("组件需要绑定主库事务管理器的 TransactionTemplate，但容器里没有任何 TransactionTemplate："
                    + "Boot 在存在唯一事务管理器时会自动注册 transactionTemplate；它没装配时（例如别的库的模板把它顶掉了）"
                    + "请自行声明一个绑定主库事务管理器（Bean " + TRANSACTION_MANAGER_BEAN + "）的 TransactionTemplate"
                    + "（组件不注册事务基础设施）");
        }
        List<String> onMaster = new ArrayList<>();
        all.forEach((name, template) -> {
            if (template.getTransactionManager() == master) {
                onMaster.add(name);
            }
        });
        if (onMaster.isEmpty()) {
            throw new IllegalStateException("组件需要的 TransactionTemplate 必须绑定主库事务管理器（Bean "
                    + TRANSACTION_MANAGER_BEAN + "），现有模板都不绑它: " + describe(all, master)
                    + "。出路：让 Boot 装配标准模板（别用别的库的模板顶掉它），或自行声明一个绑定主库事务管理器的模板");
        }
        if (onMaster.size() == 1) {
            return all.get(onMaster.get(0));
        }
        TransactionTemplate primary = primaryTemplate(beanFactory);
        if (primary != null) {
            for (String name : onMaster) {
                if (all.get(name) == primary) {
                    return primary;
                }
            }
        }
        if (onMaster.contains(TRANSACTION_TEMPLATE_BEAN)) {
            return all.get(TRANSACTION_TEMPLATE_BEAN);
        }
        throw new IllegalStateException("绑主库事务管理器的 TransactionTemplate 有多个且无法判定用哪个: " + onMaster
                + "（也不是 @Primary、也没有 Boot 标准名 " + TRANSACTION_TEMPLATE_BEAN + "）："
                + "请给应用使用的那个模板加 @Primary");
    }

    /** 模板候选的展示（名字 → 绑的事务管理器实例）：报错里点名到底用了哪个库的管理器 */
    private static String describe(Map<String, TransactionTemplate> templates, PlatformTransactionManager master) {
        return templates.entrySet().stream()
                .map(entry -> entry.getKey() + "→" + (entry.getValue().getTransactionManager() == master
                        ? "主库" : entry.getValue().getTransactionManager().getClass().getSimpleName()))
                .toList().toString();
    }

    /** {@code @Primary} 命中的模板（没有或多个 @Primary 时返回 null，交给名字规则判定） */
    private static TransactionTemplate primaryTemplate(ListableBeanFactory beanFactory) {
        try {
            return beanFactory.getBeanProvider(TransactionTemplate.class).getIfUnique();
        } catch (NoUniqueBeanDefinitionException e) {
            return null;
        }
    }

    /** 容器里的候选项（报错用），排除主库那个名字本身 */
    private static <T> List<String> candidates(ListableBeanFactory beanFactory, Class<T> type) {
        List<String> names = new ArrayList<>(List.of(beanFactory.getBeanNamesForType(type, false, false)));
        names.removeIf(DsMaster.beanNames()::contains);
        return names;
    }

}
