package com.yomi.boot.java.component.httplog.config;

import com.yomi.boot.java.component.httplog.model.HttpLogMode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 配置前缀 {@code yomi.httplog}
 *
 * @author zwei
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "yomi.httplog")
public class HttpLogProperties {

    /** body 截断上限的硬上限：sys_http_log 的 body 列为 text（64KB），留少量余量 */
    private static final int MAX_BODY_BYTES_LIMIT = 60_000;

    /** 组件开关 */
    private boolean enabled = false;

    /** 传输模式 */
    private HttpLogMode mode = HttpLogMode.MEMORY;

    /** 未配置时从 {@code spring.application.name} 推导 */
    private String serviceName;

    /** 采集配置 */
    private Collect collect = new Collect();

    /** 脱敏配置 */
    private Desensitize desensitize = new Desensitize();

    /** 缓冲配置 */
    private Buffer buffer = new Buffer();

    /** 落库配置 */
    private Store store = new Store();

    /** Redis 传输配置（mode=redis） */
    private Redis redis = new Redis();

    /** Kafka 传输配置（mode=kafka） */
    private Kafka kafka = new Kafka();

    /** 保留期清理配置 */
    private Retention retention = new Retention();

    /**
     * 采集配置（{@code yomi.httplog.collect.*}）
     */
    @Data
    public static class Collect {

        /** 采集路径（PathPattern） */
        private List<String> includePaths = List.of("/**");

        /** 排除路径（PathPattern） */
        private List<String> excludePaths = List.of(
                "/actuator/**", "/error", "/favicon.ico", "/static/**", "/views/**");

        /** 是否跳过 CORS 预检（OPTIONS） */
        private boolean skipPreflight = true;

        /** 是否采集请求头 */
        private boolean recordRequestHeaders = true;

        /** 是否采集响应头 */
        private boolean recordResponseHeaders = true;

        /** 是否采集请求体 */
        private boolean recordRequestBody = true;

        /** 是否采集响应体 */
        private boolean recordResponseBody = true;

        /** 请求体截断上限（字节）；记录超限部分被丢弃并标记 truncated。受 sys_http_log 的 text 列约束（≤60000） */
        private int maxRequestBodyBytes = 8192;

        /** 响应体截断上限（字节）；同受 text 列约束（≤60000） */
        private int maxResponseBodyBytes = 8192;

        /** 不记请求体/响应体的路径（PathPattern），如 /auth/login */
        private List<String> bodySkipPaths = List.of();

        /** 不采集的头键名（忽略名单），默认空=全采集 */
        private List<String> headerIgnore = List.of();

        /** 可信代理列表：非空时才解析 X-Forwarded-For 作为客户端IP */
        private List<String> trustedProxies = List.of();

        /** 采样率（0,1]，默认全采 */
        private double sampleRate = 1.0;

        /** 大于 0 时只记耗时达到该值（毫秒）的请求（降量逃生门） */
        private int minDurationMs = 0;

        /** 大于 0 时只记状态码达到该值的响应（降量逃生门） */
        private int statusThreshold = 0;

        /** 采集过滤器顺序（越小越靠前；默认在基础设施过滤器之后、业务之前） */
        private int filterOrder = -1000;

    }

    /**
     * 脱敏配置（{@code yomi.httplog.desensitize.*}）
     */
    @Data
    public static class Desensitize {

        /** 脱敏编排开关（组件本身无开关，引入依赖即生效；本开关只控制 httplog 是否编排） */
        private boolean enabled = true;

        /** 是否对 body（JSON/form）做结构化脱敏 */
        private boolean body = true;

        /** body 脱敏执行线程：false=后台线程（性能优先），true=请求线程（敏感原文不进缓冲队列） */
        private boolean inRequestThread = false;

        /** 键名 → 脱敏策略名（component-desensitize），作用于请求头/响应头/query/请求体/响应体；默认空 */
        private Map<String, String> keyTypes = Map.of();

        /** 显式排除的键名（优先于命中） */
        private List<String> keyExcludes = List.of();

    }

    /**
     * 缓冲配置（{@code yomi.httplog.buffer.*}）
     */
    @Data
    public static class Buffer {

        /** 缓冲容量；内存上限约 capacity × 2 × max-body-bytes */
        private int capacity = 4096;

        /** 缓冲满时的丢弃策略 */
        private DropPolicy dropPolicy = DropPolicy.DROP_NEW;

        /** 批量处理条数（与 flush-interval 先到先执行） */
        private int batchSize = 1000;

        /** 批量处理最大等待 */
        private Duration flushInterval = Duration.ofMillis(500);

        /** 停机时排空缓冲的最长等待，超时丢弃并计数 */
        private Duration shutdownDrainTimeout = Duration.ofSeconds(5);

    }

    /** 缓冲满时的丢弃策略 */
    public enum DropPolicy {

        /** 丢新：丢弃无法入队的记录（默认，先到先落库） */
        DROP_NEW,

        /** 丢旧：挤掉队首最旧记录，保留最新 */
        DROP_OLD

    }

    /**
     * 落库配置（{@code yomi.httplog.store.*}）
     */
    @Data
    public static class Store {

        /** 批量写入失败的重试次数（0=不重试直接判失败） */
        private int retryTimes = 2;

        /** 重试退避间隔 */
        private Duration retryBackoff = Duration.ofMillis(200);

    }

    /**
     * Redis 传输配置（{@code yomi.httplog.redis.*}）
     */
    @Data
    public static class Redis {

        /** 队列结构：list=单命令批量读写（推荐）；stream=消费组（需要介质级确认时可选） */
        private RedisStructure structure = RedisStructure.LIST;

        /** 队列键前缀，实际键 = 前缀 + ":" + serviceName */
        private String keyPrefix = "yomi:httplog:queue";

        /** 单次批量读取条数 */
        private int readCount = 500;

        /** 阻塞读取最长等待 */
        private Duration blockMs = Duration.ofMillis(2000);

        /** 消费组（structure=stream 专用），默认 {service-name} */
        private String group = "";

        /** 消费者名（structure=stream 专用）；空=主机名（重启保持同名，以便补投自身 PEL；同主机多实例请显式区分） */
        private String consumerName = "";

    }

    /** Redis 队列结构 */
    public enum RedisStructure {

        /** List：RPUSH 批量写入 / LPOP count 批量读取（Redis ≥ 6.2），pop 即删不重复 */
        LIST,

        /** Stream：消费组 + PEL 认领 */
        STREAM

    }

    /**
     * Kafka 传输配置（{@code yomi.httplog.kafka.*}）
     */
    @Data
    public static class Kafka {

        /** 空=默认 yomi.httplog.{service-name} */
        private String topic = "";

        /** 空=默认 {service-name}-httplog */
        private String consumerGroup = "";

        /** 消费并发数 */
        private int concurrency = 1;

        /** 多 KafkaTemplate 时指定 Bean 名（沿用 event 组件校验模式） */
        private String templateBeanName = "";

    }

    /**
     * 保留期清理配置（{@code yomi.httplog.retention.*}）
     */
    @Data
    public static class Retention {

        /** 是否启用过期清理（需 yomi.roundrobin.enabled=true） */
        private boolean enabled = true;

        /** 保留天数 */
        private int days = 7;

        /** 扫描间隔 */
        private Duration scanInterval = Duration.ofHours(1);

        /** 每批删除行数 */
        private int batchSize = 1000;

        /** 单轮最多删除行数（防止一次清理长时间占用数据库） */
        private int maxDeletePerRun = 50_000;

    }

    /** 启用时校验取值 */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (mode == null) {
            throw new IllegalStateException("yomi.httplog.mode 不能为空");
        }
        validateCollect();
        validateBuffer();
        validateStore();
        validateRetention();
        if (mode == HttpLogMode.REDIS) {
            validateRedis();
        }
        if (mode == HttpLogMode.KAFKA) {
            validateKafka();
        }
    }

    /** 校验采集相关配置 */
    private void validateCollect() {
        Collect c = collect;
        if (c.getMaxRequestBodyBytes() <= 0 || c.getMaxResponseBodyBytes() <= 0) {
            throw new IllegalStateException("yomi.httplog.collect.max-request/response-body-bytes 必须大于 0");
        }
        if (c.getMaxRequestBodyBytes() > MAX_BODY_BYTES_LIMIT || c.getMaxResponseBodyBytes() > MAX_BODY_BYTES_LIMIT) {
            throw new IllegalStateException("yomi.httplog.collect.max-request/response-body-bytes 不能超过 "
                    + MAX_BODY_BYTES_LIMIT + "：sys_http_log 的 body 列为 text（64KB），超限行会插入失败导致整批丢弃；"
                    + "如需更大请将列改为 mediumtext 并同步调整本值");
        }
        if (c.getSampleRate() <= 0 || c.getSampleRate() > 1) {
            throw new IllegalStateException("yomi.httplog.collect.sample-rate 必须在 (0,1] 区间");
        }
        if (c.getMinDurationMs() < 0) {
            throw new IllegalStateException("yomi.httplog.collect.min-duration-ms 不能为负数");
        }
        if (c.getStatusThreshold() < 0) {
            throw new IllegalStateException("yomi.httplog.collect.status-threshold 不能为负数");
        }
    }

    /** 校验缓冲与批处理配置 */
    private void validateBuffer() {
        Buffer b = buffer;
        if (b.getCapacity() <= 0) {
            throw new IllegalStateException("yomi.httplog.buffer.capacity 必须大于 0");
        }
        if (b.getBatchSize() <= 0) {
            throw new IllegalStateException("yomi.httplog.buffer.batch-size 必须大于 0");
        }
        requirePositive(b.getFlushInterval(), "yomi.httplog.buffer.flush-interval");
        requirePositive(b.getShutdownDrainTimeout(), "yomi.httplog.buffer.shutdown-drain-timeout");
    }

    /** 校验落库重试配置 */
    private void validateStore() {
        if (store.getRetryTimes() < 0) {
            throw new IllegalStateException("yomi.httplog.store.retry-times 不能为负数");
        }
        requirePositive(store.getRetryBackoff(), "yomi.httplog.store.retry-backoff");
    }

    /** 校验历史数据清理配置 */
    private void validateRetention() {
        Retention r = retention;
        if (!r.isEnabled()) {
            return;
        }
        if (r.getDays() < 1) {
            throw new IllegalStateException("yomi.httplog.retention.days 必须大于等于 1");
        }
        requirePositive(r.getScanInterval(), "yomi.httplog.retention.scan-interval");
        if (r.getBatchSize() <= 0) {
            throw new IllegalStateException("yomi.httplog.retention.batch-size 必须大于 0");
        }
        if (r.getMaxDeletePerRun() <= 0) {
            throw new IllegalStateException("yomi.httplog.retention.max-delete-per-run 必须大于 0");
        }
    }

    /** 校验 Redis 传输配置 */
    private void validateRedis() {
        Redis r = redis;
        if (r.getStructure() == null) {
            throw new IllegalStateException("yomi.httplog.redis.structure 不能为空");
        }
        if (r.getReadCount() <= 0) {
            throw new IllegalStateException("yomi.httplog.redis.read-count 必须大于 0");
        }
        requirePositive(r.getBlockMs(), "yomi.httplog.redis.block-ms");
    }

    /** 校验 Kafka 传输配置 */
    private void validateKafka() {
        if (kafka.getConcurrency() <= 0) {
            throw new IllegalStateException("yomi.httplog.kafka.concurrency 必须大于 0");
        }
    }

    /** 校验 Duration 须大于 0 */
    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 必须大于 0");
        }
    }

}
