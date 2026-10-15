package com.yomi.boot.java.component.httplog.model;

/**
 * 日志传输模式（{@code yomi.httplog.mode}）
 *
 * @author zwei
 */
public enum HttpLogMode {

    /** 进程内缓冲直落库：跳数最少，进程崩溃丢失缓冲内日志 */
    MEMORY,

    /** Redis 中转为队列（默认 List）：跨进程削峰，pop 即删不重复；需 Redis ≥ 6.2 */
    REDIS,

    /** Kafka 中转为队列：多服务汇聚，自动提交允许重复（雪花主键收敛） */
    KAFKA

}
