package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * id 生成工具类（雪花算法）。
 * <p>
 * 机器号解析优先级（datacenter 同理，未配置时默认 0）：
 * <ol>
 *     <li>Spring环境配置：yml / 系统属性 / 环境变量 三者等价，配置键 {@code yomi.id.worker-id}
 *     （环境变量形式 {@code YOMI_ID_WORKER_ID}），由框架启动监听器在环境就绪后调用 {@link #initialize} 注入</li>
 *     <li>系统属性：{@code -Dyomi.id.worker-id}（非Spring场景直接生效）</li>
 *     <li>环境变量：{@code YOMI_ID_WORKER_ID}（非Spring场景直接生效）</li>
 *     <li>主机名/POD_NAME 的稳定数字后缀（如 app-0、web-01，范围 0~31），仅当开启
 *     {@code yomi.id.allow-host-suffix=true}（环境变量 {@code YOMI_ID_ALLOW_HOST_SUFFIX}）时启用，避免跨应用同名后缀碰撞；
 *     Spring场景由监听器把绑定值显式传入 {@link #initialize}，不写进程级系统属性</li>
 *     <li>以上均未命中：抛出异常，需显式配置机器号</li>
 * </ol>
 *
 * @author zwei
 */
public abstract class IdUtils {

    private static final Logger log = LoggerFactory.getLogger(IdUtils.class);

    /** 机器号系统属性键 */
    private static final String PROP_WORKER_ID = "yomi.id.worker-id";
    /** 数据中心号系统属性键 */
    private static final String PROP_DATACENTER_ID = "yomi.id.datacenter-id";
    /** 机器号环境变量键 */
    private static final String ENV_WORKER_ID = "YOMI_ID_WORKER_ID";
    /** 数据中心号环境变量键 */
    private static final String ENV_DATACENTER_ID = "YOMI_ID_DATACENTER_ID";
    /** 是否允许从主机名后缀推导机器号的系统属性键 */
    private static final String PROP_ALLOW_HOST_SUFFIX = "yomi.id.allow-host-suffix";
    /** 是否允许从主机名后缀推导机器号的环境变量键 */
    private static final String ENV_ALLOW_HOST_SUFFIX = "YOMI_ID_ALLOW_HOST_SUFFIX";

    /** 机器号上限（5 位，0~31） */
    private static final long MAX_WORKER_ID = ~(-1L << 5L);
    /** 数据中心号上限（5 位，0~31） */
    private static final long MAX_DATACENTER_ID = ~(-1L << 5L);

    /** 主机名/POD名 以数字结尾的后缀（1~2位，对应 0~31） */
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile(".*-(\\d{1,2})$");

    /** 雪花生成器单例 */
    private static volatile Snowflake instance;

    private IdUtils() {
    }

    /**
     * 生成下一个全局唯一 id（long）
     */
    public static long nextId() {
        return instance().nextId();
    }

    /**
     * 生成下一个全局唯一 id（字符串）
     */
    public static String nextIdStr() {
        return Long.toString(nextId());
    }

    /**
     * 初始化机器号（幂等）：由Spring启动监听器在环境配置合并完成后调用，
     * 与已初始化参数不一致时抛出异常
     *
     * @param workerId         机器号；为 null 时尝试自解析（系统属性/环境变量/主机名后缀），
     *                         解析不到则不初始化（不阻断启动，首次生成 ID 时报错）
     * @param datacenterId     数据中心号（null时默认0）
     * @param allowHostSuffix  是否允许从主机名后缀推导机器号；
     *                         Spring场景传绑定值（false 即关闭）；null 时回退读系统属性/环境变量
     */
    public static synchronized void initialize(Long workerId, Long datacenterId, Boolean allowHostSuffix) {
        Long resolved = workerId;
        if (resolved == null && (datacenterId != null || Boolean.TRUE.equals(allowHostSuffix))) {
            // 只配了 datacenter 或显式开启主机名后缀：尝试解析机器号
            resolved = tryResolveWorkerId(allowHostSuffix);
        }
        if (resolved == null) {
            // 完全未配置 / 解析不到：不阻断启动（失败推迟到首次生成 ID）
            return;
        }
        long resolvedWorkerId = resolved;
        long resolvedDatacenterId = datacenterId != null ? datacenterId : 0L;
        if (instance != null) {
            if (instance.workerId == resolvedWorkerId && instance.datacenterId == resolvedDatacenterId) {
                return;
            }
            throw UtilsException.exception(
                    "雪花ID重复初始化且参数不一致：已初始化 workerId={}, datacenterId={}，新值 workerId={}, datacenterId={}",
                    instance.workerId, instance.datacenterId, resolvedWorkerId, resolvedDatacenterId);
        }
        instance = new Snowflake(resolvedWorkerId, resolvedDatacenterId,
                workerId != null ? "Spring环境配置" : "自解析");
    }

    /** 获取或懒初始化雪花算法单例 */
    private static Snowflake instance() {
        Snowflake current = instance;
        if (current != null) {
            return current;
        }
        synchronized (IdUtils.class) {
            if (instance == null) {
                instance = new Snowflake(resolveWorkerId(), resolveDatacenterId(), "自解析");
            }
            return instance;
        }
    }

    /** 解析雪花机器号；解析不到抛异常（懒初始化路径） */
    private static long resolveWorkerId() {
        Long resolved = tryResolveWorkerId(null);
        if (resolved == null) {
            throw UtilsException.exception("""
                    雪花机器号未配置，无法生成 ID。请通过以下任一方式指定 0~31 的机器号：
                    1. 配置文件yml：yomi.id.worker-id
                    2. 系统属性：-Dyomi.id.worker-id
                    3. 环境变量：YOMI_ID_WORKER_ID
                    4. 开启 yomi.id.allow-host-suffix=true 且主机名/POD_NAME 使用数字后缀（如 app-0 ~ app-31）""");
        }
        return resolved;
    }

    /** 尝试解析机器号（系统属性/环境变量/主机名后缀）；解析不到返回 null，由调用方决定报错时机 */
    private static Long tryResolveWorkerId(Boolean allowHostSuffix) {
        Long configured = readLong(PROP_WORKER_ID, ENV_WORKER_ID);
        if (configured != null) {
            assertWorkerIdInRange(configured, PROP_WORKER_ID);
            return configured;
        }
        if (isHostSuffixAllowed(allowHostSuffix)) {
            Long suffix = resolveHostSuffix();
            if (suffix != null) {
                return suffix;
            }
        }
        return null;
    }

    /** 解析数据中心号 */
    private static long resolveDatacenterId() {
        Long configured = readLong(PROP_DATACENTER_ID, ENV_DATACENTER_ID);
        if (configured == null) {
            return 0L;
        }
        assertDatacenterIdInRange(configured, PROP_DATACENTER_ID);
        return configured;
    }

    /**
     * 是否允许从主机名/POD 名数字后缀推导 workerId：显式传入值优先，未传（非Spring场景）回退系统属性/环境变量；默认关闭
     */
    private static boolean isHostSuffixAllowed(Boolean allowHostSuffix) {
        if (allowHostSuffix != null) {
            return allowHostSuffix;
        }
        String value = System.getProperty(PROP_ALLOW_HOST_SUFFIX);
        if (StringUtils.isBlank(value)) {
            value = System.getenv(ENV_ALLOW_HOST_SUFFIX);
        }
        if (StringUtils.isBlank(value)) {
            return false;
        }
        value = value.trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /** 校验机器号在 0~31 范围内 */
    private static void assertWorkerIdInRange(long workerId, String configKey) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw UtilsException.exception("worker id 必须在 0 ~ {} 之间，配置项 {} 当前: {}",
                    MAX_WORKER_ID, configKey, workerId);
        }
    }

    /** 校验数据中心号在 0~31 范围内 */
    private static void assertDatacenterIdInRange(long datacenterId, String configKey) {
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw UtilsException.exception("datacenter id 必须在 0 ~ {} 之间，配置项 {} 当前: {}",
                    MAX_DATACENTER_ID, configKey, datacenterId);
        }
    }

    /**
     * 解析主机名/POD名 的稳定数字后缀作为机器号
     */
    private static Long resolveHostSuffix() {
        for (String name : hostCandidates()) {
            Matcher matcher = NUMERIC_SUFFIX.matcher(name);
            if (matcher.matches()) {
                long value = Long.parseLong(matcher.group(1));
                if (value >= 0 && value <= 31) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 收集主机名/POD 名候选 */
    private static List<String> hostCandidates() {
        List<String> names = new ArrayList<>();
        for (String key : new String[]{"POD_NAME", "HOSTNAME"}) {
            String value = System.getenv(key);
            if (StringUtils.isNotBlank(value)) {
                names.add(value.trim());
            }
        }
        try {
            names.add(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            // 主机名不可得时跳过该候选
        }
        return names;
    }

    /** 从系统属性或环境变量读取长整型配置 */
    private static Long readLong(String propKey, String envKey) {
        String value = System.getProperty(propKey);
        if (StringUtils.isBlank(value)) {
            value = System.getenv(envKey);
        }
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw UtilsException.exception(e, "雪花机器号配置不是合法数字: {}={}", propKey, value);
        }
    }

    /** 雪花算法 ID 生成器 */
    private static final class Snowflake {

        /** 自定义纪元（2021-01-01 UTC） */
        private static final long EPOCH = 1609459200000L;

        /** worker 占用位数 */
        private static final long WORKER_ID_BITS = 5L;
        /** datacenter 占用位数 */
        private static final long DATACENTER_ID_BITS = 5L;
        /** 毫秒内序列占用位数 */
        private static final long SEQUENCE_BITS = 12L;

        /** worker 最大值 */
        private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
        /** datacenter 最大值 */
        private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
        /** 序列号掩码 */
        private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

        /** worker 在 ID 中的位移 */
        private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
        /** datacenter 在 ID 中的位移 */
        private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
        /** 时间戳在 ID 中的位移 */
        private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

        /** 机器号 */
        private final long workerId;

        /** 数据中心号 */
        private final long datacenterId;

        /** 毫秒内序列号 */
        private long sequence;

        /** 上次生成 ID 的时间戳（毫秒） */
        private long lastTimestamp = -1L;

        private Snowflake(long workerId, long datacenterId, String source) {
            this.workerId = workerId;
            this.datacenterId = datacenterId;
            if (workerId > MAX_WORKER_ID || workerId < 0) {
                throw UtilsException.exception("worker id 必须在 0 ~ {} 之间，当前: {}", MAX_WORKER_ID, workerId);
            }
            if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
                throw UtilsException.exception("datacenter id 必须在 0 ~ {} 之间，当前: {}", MAX_DATACENTER_ID, datacenterId);
            }
            log.info("雪花ID初始化完成: workerId={}, datacenterId={}, 来源: {}", workerId, datacenterId, source);
        }

        /** 雪花算法生成下一个 ID */
        private synchronized long nextId() {
            long timestamp = currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw UtilsException.exception(
                        "时钟回拨，拒绝生成 id。上次: {}，当前: {}", lastTimestamp, timestamp);
            }
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) {
                    timestamp = waitNextMillis(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }
            lastTimestamp = timestamp;
            return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                    | (datacenterId << DATACENTER_ID_SHIFT)
                    | (workerId << WORKER_ID_SHIFT)
                    | sequence;
        }

        /** 阻塞到下一毫秒时间戳 */
        private static long waitNextMillis(long lastTimestamp) {
            long timestamp = currentTimeMillis();
            while (timestamp <= lastTimestamp) {
                long sleepMillis = lastTimestamp - timestamp + 1;
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                timestamp = currentTimeMillis();
            }
            return timestamp;
        }

        /** 当前毫秒时间戳 */
        private static long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

}
