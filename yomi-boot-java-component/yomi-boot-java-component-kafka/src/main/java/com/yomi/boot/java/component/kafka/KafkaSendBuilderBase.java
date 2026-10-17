package com.yomi.boot.java.component.kafka;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 链式发送公共字段与方法。
 * <p>{@code send()} 在参数校验通过后、提交发送前即作废本实例，需重新 {@link KafkaUtils#sync()} / {@link KafkaUtils#async()}。</p>
 * <p>参数校验失败时不作废，可改参数后再次 {@code send()}。</p>
 *
 * @author zwei
 */
abstract class KafkaSendBuilderBase<T extends KafkaSendBuilderBase<T>> {

    /** 实例已 send 作废后再次发送时的提示文案 */
    private static final String SENDER_CLOSED_MESSAGE =
            "Kafka sender 已结束，请通过 KafkaUtils.sync() 或 KafkaUtils.async() 重新创建";

    /** 是否已调用 send 结束本实例 */
    private boolean terminated;

    /** 指定 KafkaTemplate Bean 名 */
    private String templateBeanName;

    /** 目标 Topic */
    private String topic;

    /** 消息 Key */
    private String key;

    /** 目标分区 */
    private Integer partition;

    /** 消息体 */
    private Object payload;

    /** 自定义 Header（Map 形式） */
    private Map<String, byte[]> headerMap;

    /** 自定义 Header（Iterable 形式，调用时已校验并复制） */
    private List<Header> headerIterable;

    /** 返回子类实例，用于链式类型 */
    protected abstract T self();

    /** 多集群时指定 {@link KafkaTemplate} 的 Spring Bean 名称；省略则用默认 Bean */
    public T template(String templateBeanName) {
        ensureOpen();
        this.templateBeanName = templateBeanName;
        return self();
    }

    /** 目标 Topic（必填） */
    public T topic(String topic) {
        ensureOpen();
        this.topic = topic;
        return self();
    }

    /** 消息 Key（可选，用于分区路由） */
    public T key(String key) {
        ensureOpen();
        this.key = key;
        return self();
    }

    /** 目标分区（可选，不指定则由 Kafka 分配） */
    public T partition(Integer partition) {
        ensureOpen();
        this.partition = partition;
        return self();
    }

    /** 消息体（必填，由 {@link KafkaTemplate} 配置的序列化器处理） */
    public T payload(Object payload) {
        ensureOpen();
        this.payload = payload;
        return self();
    }

    /** 追加一条 Header；名称为空或值为 null 时抛 {@link IllegalArgumentException} */
    public T header(String name, byte[] value) {
        ensureOpen();
        validateHeader(name, value);
        if (headerMap == null) {
            headerMap = new LinkedHashMap<>();
        }
        headerMap.put(name, value);
        return self();
    }

    /** 批量追加 Header（与 {@link #header} 合并，同名后者覆盖）；先整体校验，任一条不合法即抛 */
    public T headers(Map<String, byte[]> headers) {
        ensureOpen();
        if (headers == null || headers.isEmpty()) {
            return self();
        }
        headers.forEach(KafkaSendBuilderBase::validateHeader);
        if (headerMap == null) {
            headerMap = new LinkedHashMap<>();
        }
        headerMap.putAll(headers);
        return self();
    }

    /** 追加 Header 列表（在 Map 之后写入 Record）；调用时即校验并复制，任一条不合法即抛 */
    public T headers(Iterable<Header> headers) {
        ensureOpen();
        if (headers == null) {
            this.headerIterable = null;
            return self();
        }
        List<Header> validated = new ArrayList<>();
        for (Header header : headers) {
            if (header == null) {
                throw new IllegalArgumentException("Kafka header 不能为 null");
            }
            validateHeader(header.key(), header.value());
            validated.add(header);
        }
        this.headerIterable = validated;
        return self();
    }

    /** Header 名称与值校验：不合法直接抛 {@link IllegalArgumentException} */
    private static void validateHeader(String name, byte[] value) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Kafka header 名称不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("Kafka header 值不能为 null, name=" + name);
        }
    }

    /** 已结束的 sender 再配置即抛异常 */
    protected void ensureOpen() {
        if (terminated) {
            throw new IllegalStateException(SENDER_CLOSED_MESSAGE);
        }
    }

    /** 发送前校验必填项 */
    protected void validateBeforeSend() {
        if (StringUtils.isBlank(topic)) {
            throw new IllegalArgumentException("Kafka 发送 topic 不能为空");
        }
        if (Objects.isNull(payload)) {
            throw new IllegalArgumentException("Kafka 发送 payload 不能为 null");
        }
    }

    /** 校验通过后调用：标记 sender 已结束，复用即抛异常 */
    protected void terminate() {
        terminated = true;
    }

    /** 按当前链式参数组装 {@link ProducerRecord} */
    protected ProducerRecord<String, Object> toProducerRecord() {
        return KafkaProducerRecords.create(topic, key, payload, partition, headerMap, headerIterable);
    }

    /** 链式发送选用的 KafkaTemplate Bean 名 */
    protected String templateBeanName() {
        return templateBeanName;
    }

    /** 目标 Topic（发送后仍可读，供同步发送错误信息使用） */
    protected String topic() {
        return topic;
    }

}
