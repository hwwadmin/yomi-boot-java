package com.yomi.boot.java.component.idempotency.core;

import com.yomi.boot.java.base.utils.ExUtils;
import com.yomi.boot.java.component.ds.support.DbDuplicateKey;
import com.yomi.boot.java.component.ds.support.DsCrossDbGuard;
import com.yomi.boot.java.component.ds.support.SavepointScope;
import com.yomi.boot.java.component.idempotency.IdempotencyDataSources;
import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;
import com.yomi.boot.java.component.idempotency.exception.IdempotencyException;
import com.yomi.boot.java.component.idempotency.store.IdempotencyCacheLoad;
import com.yomi.boot.java.component.idempotency.store.IdempotencyKeys;
import com.yomi.boot.java.component.idempotency.store.IdempotencyRedisStore;
import com.yomi.boot.java.component.idempotency.support.IdempotencyResultJson;
import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.client.RedisException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * 幂等编排：定边界（记录库 + 事务）→ Redis 热读 → 分布式锁 → 占位执行业务 → 写回 Redis
 * <p>事务边界由调用方给：显式传 {@code TransactionTemplate}，或让调用点已有事务（{@code @Transactional}）。
 * 组件不创建、也不注入事务基础设施——没有边界就直接失败，绝不"各自自动提交"（那样业务回滚后这个键会卡在处理中）
 * <p>记录库跟着事务走：调用点事务落在哪个库，记录就写哪个库（注册项按数据源身份解析，见
 * {@link IdempotencyDataSources}），"记录与业务同事务"由此在单库内成立；事务绑了别的库、绑了多条库的事务、
 * 或该库未注册，都在入口直接失败
 * <p>Redis 不可用时走无锁路径：分布式锁只是"省一次 DB 写入"的优化，
 * 互斥由 {@code t_sys_idempotency_record} 唯一键 + 事务内先插占位承担，性能略降但正确性不变
 * <p>占位 INSERT 用保存点包住：唯一键冲突在 PostgreSQL 上会让事务进入 aborted（25P02），
 * 冲突后先回滚到保存点，才能读回已完成结果、外层事务才能继续
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyGuard {

    /** 记录库注册表（按数据源解析记录仓储；默认只注册主库） */
    private final IdempotencyDataSources dataSources;

    /** Redis 键前缀（按记录库再分段） */
    private final IdempotencyKeys keys;

    /** 结果 Redis 缓存 */
    private final IdempotencyRedisStore redisStore;

    /** 幂等组件配置 */
    private final IdempotencyProperties properties;

    /** 无返回值业务的幂等包装（沿用调用点已有事务） */
    public void executeVoid(IdempotencyKey key, Runnable fn) {
        executeVoid(key, null, fn);
    }

    /** 无返回值业务的幂等包装（显式事务模板：调用点没有事务时用它给出边界） */
    public void executeVoid(IdempotencyKey key, TransactionTemplate template, Runnable fn) {
        executeInternal(key, Object.class, template, () -> {
            fn.run();
            return null;
        });
    }

    /** 编程式幂等：指定返回类型与业务逻辑（沿用调用点已有事务） */
    public <T> T execute(IdempotencyKey key, Class<T> returnType, Supplier<T> fn) {
        return execute(key, returnType, null, fn);
    }

    /** 编程式幂等：指定返回类型与业务逻辑 + 显式事务模板 */
    @SuppressWarnings("unchecked")
    public <T> T execute(IdempotencyKey key, Class<T> returnType, TransactionTemplate template, Supplier<T> fn) {
        Object result = executeInternal(key, returnType, template, fn::get);
        // 基元类走未检查转换：Class.cast 对基元恒抛 ClassCastException（isInstance 对基元恒 false）
        return returnType.isPrimitive() ? (T) result : returnType.cast(result);
    }

    /** 编程式幂等：指定完整泛型返回类型（如 {@code Result<OrderVO>}），重放不丢泛型 */
    public <T> T execute(IdempotencyKey key, Type returnType, Supplier<T> fn) {
        return execute(key, returnType, null, fn);
    }

    /** 编程式幂等：指定完整泛型返回类型 + 显式事务模板 */
    @SuppressWarnings("unchecked")
    public <T> T execute(IdempotencyKey key, Type returnType, TransactionTemplate template, Supplier<T> fn) {
        return (T) executeInternal(key, returnType, template, fn::get);
    }

    /**
     * 核心流程：校验键 → 定边界 → 读缓存 → 加锁 → 双检缓存 → 事务内占位与执行 → 写缓存
     *
     * @param template 事务模板；{@code null} 表示沿用调用点已有事务
     */
    public Object executeInternal(IdempotencyKey key, Type returnType, TransactionTemplate template, Supplier<Object> fn) {
        if (!key.isValid()) {
            throw IdempotencyException.keyInvalid();
        }
        assertFitsColumns(key);
        Boundary boundary = resolveBoundary(template);
        IdempotencyKeys dsKeys = keys.forDatasource(boundary.library().name());

        IdempotencyCacheLoad cached = redisStore.load(dsKeys, key, returnType);
        if (cached.hit()) {
            // 带指纹时缓存键已并入指纹摘要：命中即表示指纹一致，异体请求会 miss 并走库内那道校验
            return cached.value();
        }

        String lockKey = dsKeys.lockKey(key);
        Lock lock;
        try {
            lock = acquireLock(lockKey);
        } catch (LockBusyException busy) {
            // 未拿到锁：读缓存或 DB 已完成结果；仍在处理中则抛 PROCESSING
            return resolveCachedOrDbResult(boundary, dsKeys, key, returnType);
        } catch (Exception e) {
            if (!ExUtils.isCausedBy(e, RedisException.class)) {
                throw e;
            }
            // Redis 不可用：走无锁路径，由 DB 唯一键保证互斥，幂等接口继续服务
            log.warn("[幂等]Redis 不可用，降级为无锁 + DB 唯一键兜底 namespace={} key={} library={}",
                    key.namespace(), key.idempotencyKey(), boundary.library().name(), e);
            return executeWithoutLock(boundary, dsKeys, key, returnType, fn, template);
        }
        try {
            return executeLocked(boundary, dsKeys, key, returnType, fn, template);
        } finally {
            releaseQuietly(lockKey, lock);
        }
    }

    /** 本次调用的边界：记录库注册项 + 调用点事务状态（显式模板的状态由模板回调给出，此处为 null） */
    private record Boundary(IdempotencyDataSources.Registration library, TransactionStatus status) {
    }

    /**
     * 定边界：记录库 = 事务落在的库（显式模板按管理器绑的数据源取，否则取调用点事务绑的数据源），
     * 且必须已注册；线程上还绑着别的库的事务时直接失败——那会让记录另起一条事务、外层回滚撤不回来
     */
    private Boundary resolveBoundary(TransactionTemplate template) {
        DataSource recordDataSource = template != null ? datasourceOfTemplate(template) : currentTransactionDatasource();
        IdempotencyDataSources.Registration library = dataSources.resolve(recordDataSource);
        if (library == null) {
            throw IdempotencyException.datasourceUnregistered(recordDataSource.getClass().getSimpleName());
        }
        String others = DsCrossDbGuard.otherBoundLibraries(recordDataSource);
        if (!others.isEmpty()) {
            throw IdempotencyException.foreignTransaction(library.name(), others);
        }
        return new Boundary(library, template == null ? currentTransactionStatus() : null);
    }

    /** 记录库 = 显式模板绑的数据源；只读与 REQUIRED 以外的传播都不能用（记录会脱离调用点事务） */
    private static DataSource datasourceOfTemplate(TransactionTemplate template) {
        if (template.isReadOnly()) {
            throw IdempotencyException.transactionInvalid("模板是只读的（readOnly=true），占位记录写不进去");
        }
        if (template.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED) {
            throw IdempotencyException.transactionInvalid("传播行为是 "
                    + propagationName(template.getPropagationBehavior()) + "，只有 REQUIRED 能让记录与业务同一条事务");
        }
        PlatformTransactionManager manager = template.getTransactionManager();
        if (!(manager instanceof DataSourceTransactionManager dataSourceManager)) {
            throw IdempotencyException.transactionInvalid("事务管理器不是 DataSourceTransactionManager（"
                    + (manager == null ? "null" : manager.getClass().getName()) + "），判定不了记录写哪个库");
        }
        return dataSourceManager.getDataSource();
    }

    /** 记录库 = 调用点事务绑定的那个数据源；事务必须真的落在数据源上，且只能有一个 */
    private static DataSource currentTransactionDatasource() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw IdempotencyException.transactionRequired();
        }
        List<DataSource> bound = DsCrossDbGuard.boundDataSources();
        if (bound.isEmpty()) {
            throw IdempotencyException.transactionRequired();
        }
        if (bound.size() > 1) {
            throw IdempotencyException.ambiguousTransaction(display(bound));
        }
        return bound.get(0);
    }

    /** 调用点事务的状态：保存点靠它（PostgreSQL 唯一键冲突后事务要能恢复可用） */
    private static TransactionStatus currentTransactionStatus() {
        try {
            return TransactionAspectSupport.currentTransactionStatus();
        } catch (NoTransactionException e) {
            throw IdempotencyException.transactionStatusUnavailable();
        }
    }

    /** 已持锁：双检缓存 → 占位执行 → 写缓存 */
    private Object executeLocked(Boundary boundary, IdempotencyKeys dsKeys, IdempotencyKey key, Type returnType,
                                 Supplier<Object> fn, TransactionTemplate template) {
        IdempotencyCacheLoad again = redisStore.load(dsKeys, key, returnType);
        if (again.hit()) {
            return again.value();
        }
        Object result = runBusiness(boundary, key, returnType, fn, template);
        cacheResult(dsKeys, key, result);
        return result;
    }

    /** Redis 不可用时的无锁路径：互斥由 DB 唯一键承担，流程与持锁路径一致 */
    private Object executeWithoutLock(Boundary boundary, IdempotencyKeys dsKeys, IdempotencyKey key, Type returnType,
                                      Supplier<Object> fn, TransactionTemplate template) {
        Object result = runBusiness(boundary, key, returnType, fn, template);
        cacheResult(dsKeys, key, result);
        return result;
    }

    /**
     * 执行业务：显式模板由模板给出事务状态，否则用调用点事务的状态
     * <p>组件不建事务基础设施：要么加入调用点事务（沿用），要么用调用方给的模板
     */
    private Object runBusiness(Boundary boundary, IdempotencyKey key, Type returnType,
                               Supplier<Object> fn, TransactionTemplate template) {
        if (template == null) {
            return runBusinessOnce(boundary.library(), key, returnType, fn, boundary.status());
        }
        return template.execute(status -> runBusinessOnce(boundary.library(), key, returnType, fn, status));
    }

    /** 先 Redis，未命中再读 DB 已完成记录 */
    private Object resolveCachedOrDbResult(Boundary boundary, IdempotencyKeys dsKeys, IdempotencyKey key, Type returnType) {
        IdempotencyCacheLoad cached = redisStore.load(dsKeys, key, returnType);
        if (cached.hit()) {
            return cached.value();
        }
        return loadCompletedResultFromDb(boundary.library(), key, returnType, false);
    }

    /** 结果写缓存；回滚时不写，缓存命中即代表库中已有已完成记录 */
    private void cacheResult(IdempotencyKeys dsKeys, IdempotencyKey key, Object result) {
        runAfterCommit(() -> redisStore.saveQuietly(dsKeys, key, result));
    }

    /**
     * 事务提交后执行；无活动事务时立即执行，事务回滚时不执行
     * <p>
     * "execute 返回"并不代表事务已提交（调用点事务或显式模板都可能更晚提交）：若在提交前写缓存，
     * 事务回滚后库中无记录、缓存里却留着成功结果，重试命中缓存直接返回成功，业务实际从未提交
     */
    static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 事务成功提交后再执行副作用，回滚时不动锁与缓存 */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 事务内：INSERT 占位 → 执行业务 → 更新为已完成
     * 唯一键冲突则只读库中已完成结果（无锁路径下即靠它承担互斥）
     * <p>
     * INSERT 用保存点包住：PostgreSQL 里唯一键冲突会把整个事务打成 aborted（后续语句 25P02），
     * 不先回滚到保存点，冲突后的读回与整个事务都会失败；MySQL 无此限制，保存点两边通用。
     * 保存点只包住 INSERT，回滚它不影响调用方在本事务里的其它写入。
     */
    private Object runBusinessOnce(IdempotencyDataSources.Registration library, IdempotencyKey key, Type returnType,
                                   Supplier<Object> fn, TransactionStatus status) {
        Instant expireTime = Instant.now().plus(properties.getRecordRetention());
        IdempotencyRecord row = new IdempotencyRecord()
                .setNamespace(key.namespace())
                .setIdempotencyKey(key.idempotencyKey())
                .setRequestHash(key.requestHash())
                .setStatus(IdempotencyStatus.PROCESSING)
                .setExpireTime(expireTime);

        SavepointScope scope = SavepointScope.open(status);
        try {
            library.repository().save(row);
        } catch (Exception e) {
            // 冲突/失败后先把事务恢复到可用状态，再做读回或抛出
            scope.rollbackTo();
            if (!DbDuplicateKey.isDuplicateKey(e)) {
                throw e;
            }
            return loadCompletedResultFromDb(library, key, returnType, true);
        } finally {
            scope.release();
        }

        Object fnResult = fn.get();
        String resultPayload = IdempotencyResultJson.marshal(fnResult);
        String resultType = fnResult == null ? null : fnResult.getClass().getName();
        library.repository().markCompleted(row.getId(), resultType, resultPayload);
        return fnResult;
    }

    /**
     * 从 DB 加载已完成结果。
     *
     * @param duplicateKeyContext true：唯一键冲突但无记录（异常态）；false：等锁未果等场景，无记录视为处理中
     */
    private Object loadCompletedResultFromDb(IdempotencyDataSources.Registration library, IdempotencyKey key,
                                             Type returnType, boolean duplicateKeyContext) {
        IdempotencyRecord existing = library.repository().findByNamespaceAndKey(key.namespace(), key.idempotencyKey());
        if (existing == null) {
            throw duplicateKeyContext
                    ? IdempotencyException.duplicateWithoutRecord()
                    : IdempotencyException.processing();
        }
        assertRequestHash(key, existing);
        if (!Objects.equals(existing.getStatus(), IdempotencyStatus.COMPLETED)) {
            throw IdempotencyException.processing();
        }
        assertResultTypeCompatible(existing, returnType);
        return IdempotencyResultJson.unmarshal(existing.getResultPayload(), returnType);
    }

    /** 身份字段必须落在列宽内：超长会在驱动层报 Data too long / value too long（指纹已归一为摘要，不受此限） */
    private static void assertFitsColumns(IdempotencyKey key) {
        assertFitsColumn("幂等命名空间", key.namespace(), IdempotencyKey.MAX_NAMESPACE_LENGTH);
        assertFitsColumn("幂等键", key.idempotencyKey(), IdempotencyKey.MAX_KEY_LENGTH);
    }

    /** 单字段列宽校验 */
    private static void assertFitsColumn(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw IdempotencyException.valueTooLong(field, value.length(), max);
        }
    }

    /**
     * 获取分布式锁：持锁时长不受限（Redisson 看门狗续期）；lease&gt;0 时按固定租期。
     * <p>仅负责获取分布式锁，便于区分 Redis 故障与锁内业务异常。
     * 等待锁时被中断则视为未拿到锁，并恢复线程中断标志
     */
    Lock acquireLock(String lockKey) {
        Lock lock = LockUtils.ofReentrant(lockKey);
        long waitMs = properties.getLockWait().toMillis();
        var lease = properties.getLockLease();
        boolean held = false;
        try {
            held = lease == null || lease.isZero()
                    ? lock.tryLock(waitMs, TimeUnit.MILLISECONDS)
                    : ((RLock) lock).tryLock(waitMs, lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!held) {
            throw new LockBusyException(lockKey);
        }
        return lock;
    }

    /** 释放分布式锁，释放失败仅记日志 */
    private void releaseQuietly(String lockKey, Lock lock) {
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            // 锁已过期易主或本就不归当前线程持有，无需释放
        } catch (Exception e) {
            log.warn("[幂等]分布式锁释放失败，可能已过期或易主 lockKey={}", lockKey, e);
        }
    }

    /** 校验请求指纹与已存记录一致 */
    private static void assertRequestHash(IdempotencyKey key, IdempotencyRecord existing) {
        if (StringUtils.isBlank(key.requestHash())) {
            return;
        }
        if (!StringUtils.equals(key.requestHash(), existing.getRequestHash())) {
            throw IdempotencyException.keyConflict();
        }
    }

    /**
     * 校验缓存结果的返回类型与当前声明是否兼容；不兼容时抛错（实现类与接口按可赋值判定）
     * <p>声明类型为基元时先归一为包装类：库里记的是运行期类名（{@code int} 方法存 {@code java.lang.Integer}），
     * 直接 {@code int.class.isAssignableFrom(Integer.class)} 恒 false，会把基元返回值误判成类型漂移
     */
    private static void assertResultTypeCompatible(IdempotencyRecord existing, Type returnType) {
        String storedType = existing.getResultType();
        if (StringUtils.isBlank(storedType)) {
            return;
        }
        Class<?> declared = ClassUtils.primitiveToWrapper(rawClass(returnType));
        if (declared == null || declared == Object.class) {
            return;
        }
        Class<?> stored;
        try {
            stored = Class.forName(storedType, false, declared.getClassLoader());
        } catch (ClassNotFoundException e) {
            log.warn("[幂等]库中记录的类型 {} 当前不可加载，跳过类型校验", storedType);
            return;
        }
        if (!declared.isAssignableFrom(stored)) {
            throw IdempotencyException.resultTypeMismatch(storedType, declared.getName());
        }
    }

    /** 从 Type 提取原始 Class */
    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    /** 传播行为名（报错用） */
    private static String propagationName(int propagationBehavior) {
        return switch (propagationBehavior) {
            case TransactionDefinition.PROPAGATION_REQUIRED -> "REQUIRED";
            case TransactionDefinition.PROPAGATION_SUPPORTS -> "SUPPORTS";
            case TransactionDefinition.PROPAGATION_MANDATORY -> "MANDATORY";
            case TransactionDefinition.PROPAGATION_REQUIRES_NEW -> "REQUIRES_NEW";
            case TransactionDefinition.PROPAGATION_NOT_SUPPORTED -> "NOT_SUPPORTED";
            case TransactionDefinition.PROPAGATION_NEVER -> "NEVER";
            case TransactionDefinition.PROPAGATION_NESTED -> "NESTED";
            default -> String.valueOf(propagationBehavior);
        };
    }

    /** 数据源清单（报错用） */
    private static String display(List<DataSource> dataSources) {
        return dataSources.stream().map(ds -> ds.getClass().getSimpleName())
                .collect(Collectors.joining("、"));
    }

}
