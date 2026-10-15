package com.yomi.boot.java.qucik.starter.testkafka.service;

import com.yomi.boot.java.base.function.SpinBarrier;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.kafka.KafkaUtils;
import com.yomi.boot.java.qucik.starter.testkafka.TestKafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka 组件流程：{@link KafkaUtils} 同步发送 + 监听器计数。
 */
@Slf4j
@Service
public class TestKafkaFlowService {

    /** 演示：等待消费毫秒数 */
    private static final long CONSUME_WAIT_MS = 8_000L;

    /** 演示计数最多保留的 msgId 条数：反复调用也不无界增长（真实业务应换带上限/TTL 的缓存） */
    private static final int MAX_TRACKED_MSG_IDS = 1000;

    /** 已处理 msgId→次数（观测去重） */
    private final Map<String, Integer> receivedByMsgId = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                /** 观测 map 满时淘汰最旧 msgId */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED_MSG_IDS;
                }
            });

    /** 发送演示消息并返回 msgId */
    public List<String> runFlow() {
        String msgId = UUID.randomUUID().toString();
        receivedByMsgId.remove(msgId);
        List<String> steps = new ArrayList<>();

        String payload = JsonUtils.toJson(Map.of("msgId", msgId));
        SendResult<String, Object> sendResult = KafkaUtils.sync()
                .topic(TestKafkaTopics.DEMO)
                .key(msgId)
                .payload(payload)
                .send();
        Assert.nonNull(sendResult, "SendResult 为空");
        Assert.nonNull(sendResult.getRecordMetadata(), "RecordMetadata 为空");
        steps.add("1.同步发送: topic=" + TestKafkaTopics.DEMO + ", msgId=" + msgId
                + ", partition=" + sendResult.getRecordMetadata().partition());

        boolean consumed = SpinBarrier.spin(() -> receivedByMsgId.getOrDefault(msgId, 0) >= 1, CONSUME_WAIT_MS);
        Assert.isTrue(consumed, "等待消费超时 msgId={}", msgId);
        steps.add("2.消费确认: msgId=" + msgId + ", count=" + receivedByMsgId.get(msgId));

        steps.add("3.流程通过");
        return steps;
    }

    /** 记录 msgId 接收次数 */
    public void onKafkaMessage(String body) {
        if (StringUtils.isBlank(body)) {
            return;
        }
        Map<String, Object> map = JsonUtils.jsonStr2Map(body);
        Object raw = map.get("msgId");
        if (raw == null) {
            return;
        }
        String msgId = String.valueOf(raw);
        receivedByMsgId.merge(msgId, 1, Integer::sum);
        log.info("[Kafka测试] 收到消息 msgId={}, bodyLength={}", msgId, body.length());
    }

}
