package com.yomi.boot.java.component.idempotency.core;

import com.yomi.boot.java.base.utils.ExUtils;
import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.ds.support.DbDuplicateKey;
import com.yomi.boot.java.component.ds.support.SavepointScope;
import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;
import com.yomi.boot.java.component.idempotency.exception.IdempotencyException;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.store.IdempotencyCacheLoad;
import com.yomi.boot.java.component.idempotency.store.IdempotencyKeys;
import com.yomi.boot.java.component.idempotency.store.IdempotencyRedisStore;
import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.client.RedisException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * 幂等编排：Redis 热读 → 分布式锁 → 事务占位执行业务 → 写回 Redis
 * <p>Redis 不可用时降级为无锁路径（fail-open）：分布式锁只是"省一次 DB 写入"的优化，
 * 互斥由 {@code sys_idempotency_record} 唯一键 + 事务内先插占位承担，性能略降但正确性不变</p>
 * <p>占位 INSERT 用保存点包住：唯一键冲突在 PostgreSQL 上会让事务进入 aborted（25P02），
 * 冲突后必须回滚到保存点才能读回已完成结果、外层事务才能继续</p>
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyGuard {

    /** 结果 JSON 序列化（与 Redis 缓存一致） */
    private static final JsonMapper MAPPER = JsonUtils.createObjectMapper().rebuild()
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    /** 幂等记录表访问 */
    private final IIdempotencyRecordRepository repository;

    /** 结果 Redis 缓存 */
    private final IdempotencyRedisStore redisStore;

    /** Redis 键前缀 */
    private final IdempotencyKeys keys;

    /** 占位与结果写回事务 */
    private final TransactionTemplate transactionTemplate;

    /** 幂等组件配置 */
    private final IdempotencyProperties properties;

    /** 供 Idempotent 切面调用：按方法返回值类型做缓存与反序列化 */
    public Object executeAround(ProceedingJoinPoint point, IdempotencyKey key) throws Throwable {
        // 取完整泛型签名（Result<OrderVO> 而非裸 Result），重放才能还原里层类型
        Type returnType = ((MethodSignature) point.getSignature()).getMethod().getGenericReturnType();
        if (returnType == void.class) {
            executeVoid(key, () -> proceedVoid(point));
            return null;
        }
        return executeInternal(key, returnType, () -> proceed(point));
    }

    /** 执行切面目标方法并统一异常类型 */
    private static Object proceed(ProceedingJoinPoint point) {
        try {
            return point.proceed();
        } catch (Throwable e) {
            if (e instanceof Error error) {
                throw error;
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(e);
        }
    }

    /** 执行无返回值切面目标 */
    private static void proceedVoid(ProceedingJoinPoint point) {
        proceed(point);
    }

    /** 无返回值业务的幂等包装 */
    public void executeVoid(IdempotencyKey key, Runnable fn) {
        executeInternal(key, Object.class, () -> {
            fn.run();
            return null;
        });
    }

    /** 编程式幂等：指定返回类型与业务逻辑 */
    @SuppressWarnings("unchecked")
    public <T> T execute(IdempotencyKey key, Class<T> returnType, Supplier<T> fn) {
        Object result = executeInternal(key, returnType, () -> fn.get());
        // 基元类走未检查转换：Class.cast 对基元恒抛 ClassCastException（isInstance 对基元恒 false）
        return returnType.isPrimitive() ? (T) result : returnType.cast(result);
    }

    /** 编程式幂等：指定完整泛型返回类型（如 {@code Result<OrderVO>}），重放不丢泛型 */
    @SuppressWarnings("unchecked")
    public <T> T execute(IdempotencyKey key, Type returnType, Supplier<T> fn) {
        return (T) executeInternal(key, returnType, fn::get);
    }

    /**
     * 核心流程：校验键 → 读缓存 → 加锁 → 双检缓存 → 事务内占位与执行 → 写缓存
     */
    public Object executeInternal(IdempotencyKey key, Type returnType, Supplier<Object> fn) {
        if (!key.isValid()) {
            throw IdempotencyException.keyInvalid();
        }

        IdempotencyCacheLoad cached = redisStore.load(key, returnType);
        if (cached.hit()) {
            // 带指纹时缓存键已并入指纹摘要：命中即等价于"指纹一致"，异体请求会 miss 并走库内那道校验
            return cached.value();
        }

        String lockKey = keys.lockKey(key);
        Lock lock;
        try {
            lock = acquireLock(lockKey);
        } catch (LockBusyException busy) {
            // 抢锁失败：读缓存或 DB 已完成结果；仍在处理中则抛 PROCESSING
            return resolveCachedOrDbResult(key, returnType);
        } catch (Exception e) {
            if (!ExUtils.isCausedBy(e, RedisException.class)) {
                throw e;
            }
            // Redis 不可用：降级为无锁 + DB 唯一键兜底继续服务，而不是让所有幂等接口 500
            log.warn("[幂等]Redis 不可用，降级为无锁 + DB 唯一键兜底 namespace={} key={}",
                    key.namespace(), key.idempotencyKey(), e);
            return executeWithoutLock(key, returnType, fn);
        }
        try {
            return executeLocked(key, returnType, fn);
        } finally {
            releaseQuietly(lockKey, lock);
        }
    }

    /**
     * 抢锁：持锁时长不受限（Redisson 看门狗续期）；lease&gt;0 时按固定租期。
     * <p>仅负责获取分布式锁，便于区分 Redis 故障与锁内业务异常。
     * 等待锁时被中断则视为未抢到锁，并恢复线程中断标志</p>
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

    /** 已持锁：双检缓存 → 事务内占位执行 → 写缓存 */
    private Object executeLocked(IdempotencyKey key, Type returnType, Supplier<Object> fn) {
        IdempotencyCacheLoad again = redisStore.load(key, returnType);
        if (again.hit()) {
            return again.value();
        }
        Object result = transactionTemplate.execute(status -> runBusinessOnce(key, returnType, fn, status));
        cacheResult(key, result);
        return result;
    }

    /** Redis 不可用时的无锁路径：互斥由 DB 唯一键承担，流程与持锁路径一致 */
    private Object executeWithoutLock(IdempotencyKey key, Type returnType, Supplier<Object> fn) {
        Object result = transactionTemplate.execute(status -> runBusinessOnce(key, returnType, fn, status));
        cacheResult(key, result);
        return result;
    }

    /** 先 Redis，未命中再读 DB 已完成记录 */
    private Object resolveCachedOrDbResult(IdempotencyKey key, Type returnType) {
        IdempotencyCacheLoad cached = redisStore.load(key, returnType);
        if (cached.hit()) {
            return cached.value();
        }
        return loadCompletedResultFromDb(key, returnType, false);
    }

    /** 结果写缓存；回滚时不写，保证"缓存命中"等价于"库中已有已完成记录" */
    private void cacheResult(IdempotencyKey key, Object result) {
        runAfterCommit(() -> redisStore.saveQuietly(key, result));
    }

    /**
     * 事务提交后执行；无活动事务时立即执行，事务回滚时不执行
     * <p>
     * 守卫可能运行在调用方已有的事务中（{@link TransactionTemplate} 默认传播 REQUIRED 会加入外层事务而不提交），
     * 此时"execute 返回"并不代表事务已提交：若在提交前写缓存，外层事务回滚后库中无记录、
     * 缓存里却留着成功结果，重试命中缓存直接返回成功，业务实际从未提交。
     */
    static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 事务成功提交后再执行副作用，避免回滚后仍释放锁/写缓存 */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 事务内：INSERT 抢占位 → 执行业务 → 更新为已完成
     * 唯一键冲突则只读库中已完成结果（无锁降级路径下即靠它承担互斥）
     * <p>
     * INSERT 用保存点包住：PostgreSQL 里唯一键冲突会把整个事务打成 aborted（后续语句 25P02），
     * 不先回滚到保存点，冲突后的读回与整个事务都会硬失败；MySQL 无此限制，保存点两边通用。
     * 保存点只包住 INSERT，回滚它不影响调用方在本事务里的其它写入。
     */
    private Object runBusinessOnce(IdempotencyKey key, Type returnType, Supplier<Object> fn, TransactionStatus status) {
        Instant expireTime = Instant.now().plus(properties.getRecordRetention());
        IdempotencyRecord row = new IdempotencyRecord()
                .setNamespace(key.namespace())
                .setIdempotencyKey(key.idempotencyKey())
                .setRequestHash(key.requestHash())
                .setStatus(IdempotencyStatus.PROCESSING)
                .setExpireTime(expireTime);

        SavepointScope scope = SavepointScope.open(status);
        try {
            repository.save(row);
        } catch (Exception e) {
            // 冲突/失败后先把事务恢复到可用状态，再做读回或抛出
            scope.rollbackTo();
            if (!DbDuplicateKey.isDuplicateKey(e)) {
                throw e;
            }
            return loadCompletedResultFromDb(key, returnType, true);
        } finally {
            scope.release();
        }

        Object fnResult = fn.get();
        String resultPayload = marshalResult(fnResult);
        String resultType = fnResult == null ? null : fnResult.getClass().getName();
        repository.markCompleted(row.getId(), resultType, resultPayload);
        return fnResult;
    }

    /**
     * 从 DB 加载已完成结果。
     *
     * @param duplicateKeyContext true：唯一键冲突但无记录（异常态）；false：抢锁等待等场景，无记录视为处理中
     */
    private Object loadCompletedResultFromDb(IdempotencyKey key, Type returnType, boolean duplicateKeyContext) {
        IdempotencyRecord existing = repository.findByNamespaceAndKey(key.namespace(), key.idempotencyKey());
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
        return unmarshalResult(existing.getResultPayload(), returnType);
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
     * 直接 {@code int.class.isAssignableFrom(Integer.class)} 恒 false，会把基元返回值误判成类型漂移</p>
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

    /** 序列化业务返回值为 JSON */
    private static String marshalResult(Object value) {
        if (value == null) {
            return null;
        }
        String json = MAPPER.writeValueAsString(value);
        if ("null".equals(json)) {
            return null;
        }
        return json;
    }

    /** 反序列化业务返回值 */
    private static Object unmarshalResult(String data, Type type) {
        if (StringUtils.isBlank(data)) {
            return null;
        }
        return MAPPER.readValue(data, MAPPER.constructType(type));
    }

}
