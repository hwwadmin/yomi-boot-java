package com.yomi.boot.java.component.idempotency;

import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 幂等组件唯一入口（静态调用，需 {@code yomi.idempotency.enabled=true}）：
 * 把写逻辑包进 {@code execute} / {@code run} 的闭包，同一 namespace + 幂等键只执行一次，重放返回首次结果。
 * <p>事务边界由调用方给：调用点已有事务（{@code *UseCase#execute} 上的 {@code @Transactional}）就直接沿用，
 * 没有事务时把 {@code TransactionTemplate} 显式传进来——组件不创建事务，两者都没有就在入口报错。
 * 记录库跟着事务走（默认只有主库可用，附加库要用幂等先经 {@link IdempotencyDataSources} 注册）。
 *
 * @author zwei
 */
public abstract class IdempotencyUtils {

    /** 生成新的幂等键，常用于客户端 Idempotency-Key 请求头 */
    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** 构造幂等键（无请求指纹） */
    public static IdempotencyKey key(String namespace, String idempotencyKey) {
        return new IdempotencyKey(namespace, idempotencyKey, null);
    }

    /** 构造幂等键（含请求指纹，同键不同参时可校验；指纹不限长，构造时归一为 SHA-256 摘要） */
    public static IdempotencyKey key(String namespace, String idempotencyKey, String requestHash) {
        return new IdempotencyKey(namespace, idempotencyKey, requestHash);
    }

    /**
     * 有返回值的幂等执行（沿用调用点已有事务）
     *
     * @param namespace      命名空间，非空
     * @param idempotencyKey 幂等键，非空
     * @param returnType     结果类型（用于缓存反序列化）
     * @param fn             仅在本请求首次占位成功时执行
     */
    public static <T> T execute(String namespace, String idempotencyKey, Class<T> returnType, Supplier<T> fn) {
        return guard().execute(key(namespace, idempotencyKey), returnType, fn);
    }

    /**
     * 有返回值的幂等执行（显式事务模板：调用点没有事务时用它给出边界，须为 REQUIRED 且非只读）
     */
    public static <T> T execute(String namespace, String idempotencyKey, Class<T> returnType,
                                TransactionTemplate template, Supplier<T> fn) {
        return guard().execute(key(namespace, idempotencyKey), returnType, template, fn);
    }

    /**
     * 有返回值的幂等执行（完整 IdempotencyKey）
     */
    public static <T> T execute(IdempotencyKey idempotencyKey, Class<T> returnType, Supplier<T> fn) {
        return guard().execute(idempotencyKey, returnType, fn);
    }

    /**
     * 有返回值的幂等执行（完整 IdempotencyKey + 显式事务模板）
     */
    public static <T> T execute(IdempotencyKey idempotencyKey, Class<T> returnType,
                                TransactionTemplate template, Supplier<T> fn) {
        return guard().execute(idempotencyKey, returnType, template, fn);
    }

    /**
     * 有返回值的幂等执行（完整泛型返回类型，如 {@code new TypeReference&lt;Result&lt;OrderVO&gt;&gt;(){}.getType()}，重放不丢里层泛型）
     */
    public static <T> T execute(String namespace, String idempotencyKey, Type returnType, Supplier<T> fn) {
        return guard().execute(key(namespace, idempotencyKey), returnType, fn);
    }

    /**
     * 有返回值的幂等执行（完整泛型返回类型 + 显式事务模板）
     */
    public static <T> T execute(String namespace, String idempotencyKey, Type returnType,
                                TransactionTemplate template, Supplier<T> fn) {
        return guard().execute(key(namespace, idempotencyKey), returnType, template, fn);
    }

    /** 有返回值的幂等执行（完整 IdempotencyKey + 完整泛型返回类型） */
    public static <T> T execute(IdempotencyKey idempotencyKey, Type returnType, Supplier<T> fn) {
        return guard().execute(idempotencyKey, returnType, fn);
    }

    /** 有返回值的幂等执行（完整 IdempotencyKey + 完整泛型返回类型 + 显式事务模板） */
    public static <T> T execute(IdempotencyKey idempotencyKey, Type returnType,
                                TransactionTemplate template, Supplier<T> fn) {
        return guard().execute(idempotencyKey, returnType, template, fn);
    }

    /**
     * 无返回值的幂等执行（沿用调用点已有事务）
     */
    public static void run(String namespace, String idempotencyKey, Runnable fn) {
        guard().executeVoid(key(namespace, idempotencyKey), fn);
    }

    /**
     * 无返回值的幂等执行（显式事务模板）
     */
    public static void run(String namespace, String idempotencyKey, TransactionTemplate template, Runnable fn) {
        guard().executeVoid(key(namespace, idempotencyKey), template, fn);
    }

    /**
     * 无返回值的幂等执行（完整 IdempotencyKey）
     */
    public static void run(IdempotencyKey idempotencyKey, Runnable fn) {
        guard().executeVoid(idempotencyKey, fn);
    }

    /**
     * 无返回值的幂等执行（完整 IdempotencyKey + 显式事务模板）
     */
    public static void run(IdempotencyKey idempotencyKey, TransactionTemplate template, Runnable fn) {
        guard().executeVoid(idempotencyKey, template, fn);
    }

    /** 获取幂等守卫 Bean */
    public static IdempotencyGuard guard() {
        return SpringUtils.getBean(IdempotencyGuard.class);
    }

    private IdempotencyUtils() {
    }

}
