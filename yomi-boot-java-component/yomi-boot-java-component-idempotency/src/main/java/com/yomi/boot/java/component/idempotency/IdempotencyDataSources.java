package com.yomi.boot.java.component.idempotency;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.IdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 幂等记录库注册门面（装配期使用）：注册哪个数据源，哪个库就获得「自动清理」并允许放幂等记录。
 * <p>
 * 默认只注册主库（组件自己完成，注册名 {@value #MASTER}）；业务附加库要用幂等，在自己的 {@code @Configuration}
 * 里把该库的 {@code SqlSessionFactory} 交给 {@link #register(String, SqlSessionFactory)}——注册名会并进 Redis 键
 * 与清理任务名，所以要稳定、各实例一致。注册必须在装配期完成，装配结束后注册表冻结，再注册即失败。
 * <p>
 * 记录库跟着事务走：调用点的事务落哪个库，幂等记录就写哪个库（守卫按数据源身份解析），这就是"记录与业务同事务"
 * 的实现方式；库没注册时守卫在入口直接报错，不会静默写到别的库去。
 *
 * @author zwei
 */
@Slf4j
public class IdempotencyDataSources {

    /** 主库的注册名（组件默认注册项，对应 component-ds 装配的主数据源） */
    public static final String MASTER = "master";

    /** 注册名允许的字符：进 Redis 键与任务名，禁止分隔符与空白 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** 组件记录表 Mapper XML（jar 内路径） */
    private static final String MAPPER_XML = "mapper/IdempotencyRecordMapper.xml";

    /** 数据源身份 → 注册项：装配期写、运行期只读，按不可变快照发布 */
    private volatile Map<DataSource, Registration> byDataSource = Map.of();

    /** 注册名 → 注册项（重复检查、日志与任务装配用） */
    private volatile Map<String, Registration> byName = Map.of();

    /** 注册表是否已冻结（装配期结束） */
    private volatile boolean frozen;

    /**
     * 注册一个数据源（业务附加库用这个）：从该库工厂建记录仓储，并确保记录表 Mapper 与方言语句在该工厂里
     *
     * @param name    注册名（非空，{@code [A-Za-z0-9_-]}，≤32 字符）
     * @param factory 该库自己的 SqlSessionFactory（复用业务那套链与方言解析）
     */
    public synchronized void register(String name, SqlSessionFactory factory) {
        if (factory == null) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 必须给出该库的 SqlSessionFactory");
        }
        DataSource dataSource = dataSourceOf(name, factory);
        register(name, dataSource, repositoryOf(name, factory, factory.getConfiguration()));
    }

    /**
     * 注册一个数据源并直接给出记录仓储（主库默认注册与自定义仓储用）
     *
     * @param name       注册名
     * @param dataSource 该库的数据源（事务管理器绑定的就是它）
     * @param repository 该库的幂等记录仓储
     */
    public synchronized void register(String name, DataSource dataSource, IIdempotencyRecordRepository repository) {
        assertName(name);
        if (frozen) {
            throw new IllegalStateException("[幂等]记录库注册表已冻结，不再接受注册（" + name + "）："
                    + "注册只能在装配期完成（业务 @Configuration 里让 Bean 依赖 IdempotencyDataSources 后调用）");
        }
        if (dataSource == null || repository == null) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 必须给出数据源与记录仓储");
        }
        Registration sameName = byName.get(name);
        if (sameName != null) {
            throw new IllegalStateException("[幂等]记录库注册名重复: " + name + "（已注册的数据源: "
                    + sameName.dataSource().getClass().getSimpleName() + "）");
        }
        Registration sameSource = byDataSource.get(dataSource);
        if (sameSource != null) {
            throw new IllegalStateException("[幂等]同一个数据源不能注册两次: 已注册为 " + sameSource.name()
                    + "，不能再注册为 " + name);
        }
        Registration registration = new Registration(name, dataSource, repository);
        byName = Collections.unmodifiableMap(append(byName, name, registration));
        byDataSource = Collections.unmodifiableMap(append(byDataSource, dataSource, registration));
        log.info("[幂等]已注册记录库: name={}, dataSource={}", name, dataSource.getClass().getSimpleName());
    }

    /** 冻结注册表（装配期结束）：运行期不再接受注册，避免请求打进来时记录库还在变 */
    public synchronized void freeze() {
        frozen = true;
        log.info("[幂等]记录库注册表已冻结: {}", byName.keySet());
    }

    /** 按数据源身份解析注册项；未注册返回 {@code null} */
    public Registration resolve(DataSource dataSource) {
        return byDataSource.get(dataSource);
    }

    /** 全部注册项（按注册顺序，主库在前）：清理任务与日志用 */
    public Collection<Registration> registrations() {
        return byName.values();
    }

    /** 一条注册：注册名 + 该库的数据源 + 该库的记录仓储 */
    public record Registration(String name, DataSource dataSource, IIdempotencyRecordRepository repository) {
    }

    /** 注册名校验：进 Redis 键与任务名，字符集与长度都要卡住 */
    private static void assertName(String name) {
        if (StringUtils.isBlank(name) || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalStateException("[幂等]记录库注册名不合法: [" + name
                    + "]，只允许字母数字与 - _（会并进 Redis 键与清理任务名），长度 1~32");
        }
    }

    /** 注册项追加到不可变快照（保留注册顺序） */
    private static <K> Map<K, Registration> append(Map<K, Registration> source, K key, Registration value) {
        Map<K, Registration> next = new LinkedHashMap<>(source);
        next.put(key, value);
        return next;
    }

    /** 取工厂绑定的数据源：事务管理器绑的也是它，注册后按身份解析 */
    private static DataSource dataSourceOf(String name, SqlSessionFactory factory) {
        DataSource dataSource = factory.getConfiguration().getEnvironment().getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 失败：工厂没绑定数据源"
                    + "（SqlSessionFactory 的 Environment 里没有 DataSource）");
        }
        return dataSource;
    }

    /** 从该库工厂建记录仓储：先确保 Mapper 与方言语句已注册进该工厂 */
    private static IIdempotencyRecordRepository repositoryOf(String name, SqlSessionFactory factory,
                                                             Configuration configuration) {
        if (!(configuration instanceof MybatisConfiguration)) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 的工厂不是 MyBatis-Plus 工厂（"
                    + configuration.getClass().getName() + "）：记录表的增删改查依赖 MP 注入的基础语句，"
                    + "请按《组件使用说明》的多数据源模板用 MybatisSqlSessionFactoryBean 装配附加库");
        }
        ensureMapper(name, configuration);
        return new IdempotencyRecordRepository(new SqlSessionTemplate(factory).getMapper(IdempotencyRecordMapper.class));
    }

    /** 确保记录表 Mapper 与该库方言的语句都在工厂里（工厂自己扫过组件 XML 时什么都不做） */
    private static void ensureMapper(String name, Configuration configuration) {
        if (!configuration.hasMapper(IdempotencyRecordMapper.class)) {
            configuration.addMapper(IdempotencyRecordMapper.class);
        }
        if (configuration.hasStatement(statementId("deleteExpired"))) {
            return;
        }
        if (StringUtils.isBlank(configuration.getDatabaseId())) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 失败：该工厂没解析出方言（databaseId 为空），"
                    + "组件按方言写的限额删除语句无法注册。请在附加库工厂上 setDatabaseIdProvider(DsDatabaseIdProvider)");
        }
        try (InputStream xml = Resources.getResourceAsStream(MAPPER_XML)) {
            if (xml == null) {
                throw new IllegalStateException("[幂等]注册记录库 " + name + " 失败：jar 内缺少 " + MAPPER_XML);
            }
            new XMLMapperBuilder(xml, configuration, MAPPER_XML, configuration.getSqlFragments()).parse();
        } catch (IOException e) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 失败：读取 " + MAPPER_XML + " 出错", e);
        }
        if (!configuration.hasStatement(statementId("deleteExpired"))) {
            throw new IllegalStateException("[幂等]注册记录库 " + name + " 失败：组件语句未注册进该工厂"
                    + "（方言=" + configuration.getDatabaseId() + "，只内置 MySQL/PostgreSQL）");
        }
    }

    /** Mapper 语句 id */
    private static String statementId(String method) {
        return IdempotencyRecordMapper.class.getName() + "." + method;
    }

}
