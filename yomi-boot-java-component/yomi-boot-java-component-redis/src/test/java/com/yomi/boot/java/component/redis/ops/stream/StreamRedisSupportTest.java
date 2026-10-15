package com.yomi.boot.java.component.redis.ops.stream;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.ConsumerStreamReadRequest;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.data.redis.stream.Subscription;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stream 消费语义（离线驱动）：
 * <ol>
 *   <li>listen 必须注册"手动 ack"订阅（消息进 PEL），不得再用 receiveAutoAck（NOACK 异常即丢）；</li>
 *   <li>handler 成功 → 立即 XACK；handler 失败 → 不 ACK、异常不逃逸（逃逸会因 cancelOnError 默认值取消整个订阅）；</li>
 *   <li>订阅永不被处理/读取异常取消；</li>
 *   <li>claimAndRedeliver 认领超时未确认消息并重新投递，成功自动 ACK。</li>
 * </ol>
 */
class StreamRedisSupportTest {

    @Test
    void listenRegistersManualAckSubscriptionAndNeverCancelsOnError() {
        StubTemplate template = new StubTemplate();
        StubContainer container = new StubContainer();
        StreamRedisSupport support = new StreamRedisSupport(template, container);

        support.listen("orders", "grp", "c-1", (stream, id, body) -> {
        });

        assertEquals(1, container.registered.size(), "listen 必须经 register 注册订阅");
        assertEquals(0, container.receiveAutoAckCalls, "不得再使用 receiveAutoAck（NOACK 会让失败消息永久丢失）");
        StreamReadRequest<String> request = container.registered.get(0).request();
        ConsumerStreamReadRequest<String> consumerRequest =
                assertInstanceOf(ConsumerStreamReadRequest.class, request, "订阅必须带消费组信息");
        assertFalse(consumerRequest.isAutoAcknowledge(), "必须让消息进入 PEL（autoAcknowledge=false）");
        assertEquals("c-1", consumerRequest.getConsumer().getName());
        assertEquals("grp", consumerRequest.getConsumer().getGroup());
        assertNotNull(consumerRequest.getStreamOffset());
        assertFalse(request.getCancelSubscriptionOnError().test(new RuntimeException("redis blip")),
                "处理失败或 Redis 抖动不得取消订阅（默认策略是任何异常都取消）");
    }

    @Test
    void successfulHandlingAcksImmediately() {
        StubTemplate template = new StubTemplate();
        StubContainer container = new StubContainer();
        StreamRedisSupport support = new StreamRedisSupport(template, container);
        List<String> handled = new ArrayList<>();

        support.listen("orders", "grp", "c-1", (stream, id, body) -> handled.add(id));
        drive(container, record("1-0", "ok"));

        assertEquals(List.of("1-0"), handled);
        assertEquals(List.of("1-0"), template.acks, "处理成功必须立即 XACK");
    }

    @Test
    void handlerFailureKeepsMessagePendingAndDoesNotEscape() {
        StubTemplate template = new StubTemplate();
        StubContainer container = new StubContainer();
        StreamRedisSupport support = new StreamRedisSupport(template, container);

        support.listen("orders", "grp", "c-1", (stream, id, body) -> {
            throw new IllegalStateException("业务处理失败");
        });

        // 异常若逃出 listener，容器会按 cancelSubscriptionOnError 取消订阅（默认任何异常都取消）
        assertDoesNotThrow(() -> drive(container, record("1-0", "x")));
        assertTrue(template.acks.isEmpty(), "处理失败不得 ACK：消息必须留在 PEL 等待重投");
    }

    @Test
    void ackFailureDoesNotEscapeAndKeepsMessagePending() {
        StubTemplate template = new StubTemplate();
        template.failAck = true;
        StubContainer container = new StubContainer();
        StreamRedisSupport support = new StreamRedisSupport(template, container);

        support.listen("orders", "grp", "c-1", (stream, id, body) -> {
        });

        assertDoesNotThrow(() -> drive(container, record("1-0", "x")), "ACK 失败只记日志，不得逃逸取消订阅");
        assertTrue(template.acks.isEmpty());
    }

    @Test
    void claimAndRedeliverRedeliversStalePendingMessages() {
        StubTemplate template = new StubTemplate();
        template.pending = new PendingMessages("grp", List.of(new PendingMessage(
                RecordId.of("2-0"), Consumer.from("grp", "c-dead"), Duration.ofMinutes(10), 1)));
        template.claimed = List.of(MapRecord.create("orders", Map.of("payload", "retry")).withId(RecordId.of("2-0")));
        StreamRedisSupport support = new StreamRedisSupport(template, new StubContainer());
        List<String> handled = new ArrayList<>();

        int processed = support.claimAndRedeliver("orders", "grp", "c-1", Duration.ofMinutes(5),
                (stream, id, body) -> handled.add(id));

        assertEquals(1, processed);
        assertEquals(List.of("2-0"), handled, "超时未确认的消息必须被重新交给 handler");
        assertEquals(List.of("2-0"), template.acks, "重投成功后自动 ACK");
    }

    @Test
    void claimAndRedeliverSkipsFreshPendingMessages() {
        StubTemplate template = new StubTemplate();
        template.pending = new PendingMessages("grp", List.of(new PendingMessage(
                RecordId.of("3-0"), Consumer.from("grp", "c-2"), Duration.ofSeconds(10), 1)));
        StreamRedisSupport support = new StreamRedisSupport(template, new StubContainer());

        int processed = support.claimAndRedeliver("orders", "grp", "c-1", Duration.ofMinutes(5),
                (stream, id, body) -> {
                    throw new AssertionError("未超时的消息不应被认领");
                });

        assertEquals(0, processed, "未达到 minIdle 的消息不认领（避免与在途处理重复）");
        assertTrue(template.claimed.isEmpty(), "无超时消息时不应触发 XCLAIM");
    }

    /** 驱动 Stream 测试消费循环 */
    private static void drive(StubContainer container, MapRecord<String, String, String> record) {
        container.registered.get(0).listener().onMessage(record);
    }

    /** 构造 Stream 测试记录 */
    private static MapRecord<String, String, String> record(String id, String payload) {
        return MapRecord.create("orders", Map.of(StreamRedisSupport.PAYLOAD_FIELD, payload)).withId(RecordId.of(id));
    }

    /** 覆盖 opsForStream：记录 ACK、编排 pending/claim 结果，其余命令成功返回 */
    static class StubTemplate extends StringRedisTemplate {

        final List<String> acks = new ArrayList<>();
        PendingMessages pending = new PendingMessages("grp", List.of());
        List<MapRecord<String, String, String>> claimed = List.of();
        boolean failAck;

        @Override
        @SuppressWarnings("unchecked")
        public <HK, HV> StreamOperations<String, HK, HV> opsForStream() {
            return (StreamOperations<String, HK, HV>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{StreamOperations.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "acknowledge" -> {
                            if (failAck) {
                                throw new RedisConnectionFailureException("ack failed");
                            }
                            Object last = args[args.length - 1];
                            if (last instanceof RecordId[] ids && ids.length > 0) {
                                acks.add(ids[0].getValue());
                            }
                            yield 1L;
                        }
                        case "pending" -> pending;
                        case "claim" -> claimed;
                        case "createGroup" -> null;
                        default -> null;
                    });
        }
    }

    /** 覆盖容器：记录 register/receiveAutoAck，捕获 listener 供测试驱动 */
    static class StubContainer implements StreamMessageListenerContainer<String, MapRecord<String, String, String>> {

        record Registered(StreamReadRequest<String> request, StreamListener<String, MapRecord<String, String, String>> listener) {
        }

        final List<Registered> registered = new ArrayList<>();
        int receiveAutoAckCalls;

        @Override
        public Subscription register(StreamReadRequest<String> request,
                                     StreamListener<String, MapRecord<String, String, String>> listener) {
            registered.add(new Registered(request, listener));
            return subscription();
        }

        @Override
        public Subscription receiveAutoAck(Consumer consumer, StreamOffset<String> streamOffset,
                                          StreamListener<String, MapRecord<String, String, String>> listener) {
            receiveAutoAckCalls++;
            throw new AssertionError("不得使用 receiveAutoAck");
        }

        @Override
        public void remove(Subscription subscription) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        /** 构造 Stream 订阅句柄 */
        private static Subscription subscription() {
            return new Subscription() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isActive() {
                    return true;
                }

                @Override
                public boolean await(Duration timeout) {
                    return true;
                }
            };
        }
    }

}
