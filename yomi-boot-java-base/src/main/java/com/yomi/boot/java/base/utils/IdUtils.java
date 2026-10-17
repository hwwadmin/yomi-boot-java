package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * id 生成工具类（雪花算法）。
 * <p>
 * 机器号需显式配置（0~31），不做自动推导：Spring 应用由框架启动监听器读取
 * {@code yomi.id.worker-id} 后调用 {@link #initialize}，非 Spring 场景自行调用；
 * 未初始化即生成 ID 抛异常，数据中心号未配置时默认 0。
 *
 * @author zwei
 */
public abstract class IdUtils {

    private static final Logger log = LoggerFactory.getLogger(IdUtils.class);

    /** 机器号上限（5 位，0~31） */
    private static final long MAX_WORKER_ID = ~(-1L << 5L);
    /** 数据中心号上限（5 位，0~31） */
    private static final long MAX_DATACENTER_ID = ~(-1L << 5L);

    /** 雪花生成器单例 */
    private static volatile Snowflake instance;

    private IdUtils() {
    }

    /**
     * 生成下一个全局唯一 id（long）；未初始化时抛异常
     */
    public static long nextId() {
        return instance().nextId();
    }

    /**
     * 生成下一个全局唯一 id（字符串）；未初始化时抛异常
     */
    public static String nextIdStr() {
        return Long.toString(nextId());
    }

    /**
     * 初始化雪花参数（幂等）：在生成 ID 前调用，重复初始化且参数不一致时抛异常
     *
     * @param workerId     机器号，0~31，多实例部署时各实例互不相同
     * @param datacenterId 数据中心号，0~31
     */
    public static synchronized void initialize(long workerId, long datacenterId) {
        assertInRange(workerId, MAX_WORKER_ID, "worker id", "yomi.id.worker-id");
        assertInRange(datacenterId, MAX_DATACENTER_ID, "datacenter id", "yomi.id.datacenter-id");
        if (instance != null) {
            if (instance.workerId == workerId && instance.datacenterId == datacenterId) {
                return;
            }
            throw UtilsException.exception(
                    "雪花ID重复初始化且参数不一致：已初始化 workerId={}, datacenterId={}，新值 workerId={}, datacenterId={}",
                    instance.workerId, instance.datacenterId, workerId, datacenterId);
        }
        instance = new Snowflake(workerId, datacenterId);
    }

    /** 获取雪花算法单例；未初始化直接抛异常 */
    private static Snowflake instance() {
        Snowflake current = instance;
        if (current == null) {
            throw UtilsException.exception("雪花ID未初始化：请配置 yomi.id.worker-id（0~31）；"
                    + "非 Spring 场景请先调用 IdUtils.initialize(workerId, datacenterId)");
        }
        return current;
    }

    /** 校验雪花号在 0 ~ max 范围内 */
    private static void assertInRange(long value, long max, String name, String configKey) {
        if (value < 0 || value > max) {
            throw UtilsException.exception("{} 必须在 0 ~ {} 之间（配置项 {}），当前: {}", name, max, configKey, value);
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

        private Snowflake(long workerId, long datacenterId) {
            this.workerId = workerId;
            this.datacenterId = datacenterId;
            log.info("雪花ID初始化完成: workerId={}, datacenterId={}", workerId, datacenterId);
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
