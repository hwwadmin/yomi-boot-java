package com.yomi.boot.java.component.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaSenderTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    @BeforeEach
    void bindTemplate() throws Exception {
        KafkaTemplateHolder.clearTemplateCache();
        Field field = KafkaTemplateHolder.class.getDeclaredField("defaultKafkaTemplate");
        field.setAccessible(true);
        field.set(null, kafkaTemplate);
    }

    @AfterEach
    void clearCache() {
        KafkaTemplateHolder.clearTemplateCache();
    }

    @Test
    void producerRecordSetsPartitionAndHeaders() {
        ProducerRecord<String, Object> record = KafkaProducerRecords.create(
                "orders",
                "k1",
                "body",
                2,
                Map.of("traceId", "abc".getBytes(StandardCharsets.UTF_8)),
                List.of(new RecordHeader("tenant", "t1".getBytes(StandardCharsets.UTF_8))));

        assertEquals("orders", record.topic());
        assertEquals("k1", record.key());
        assertEquals("body", record.value());
        assertEquals(2, record.partition());
        assertTrue(record.headers().headers("traceId").iterator().hasNext());
        assertTrue(record.headers().headers("tenant").iterator().hasNext());
    }

    @Test
    void syncSendReturnsResult() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        SendResult<String, Object> actual =
                KafkaUtils.sync().topic("topic-a").key("key").payload("payload").send();

        assertSame(sendResult, actual);
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void syncSendTimeoutWrapsIllegalStateException() {
        CompletableFuture<SendResult<String, Object>> pending = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(pending);

        KafkaSyncSender sender = KafkaUtils.sync().topic("topic-timeout").payload("payload").timeout(1, TimeUnit.MILLISECONDS);
        IllegalStateException ex = assertThrows(IllegalStateException.class, sender::send);
        assertTrue(ex.getMessage().contains("topic=topic-timeout"));
        assertTrue(ex.getCause() instanceof TimeoutException);
    }

    @Test
    void syncSendFailureWrapsIllegalStateException() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new ExecutionException("boom", new RuntimeException("root"))));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> KafkaUtils.sync().topic("topic-fail").payload("payload").send());
        assertTrue(ex.getMessage().contains("topic=topic-fail"));
        assertNotNull(ex.getCause());
    }

    @Test
    void asyncSendReturnsFutureFromTemplate() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        CompletableFuture<SendResult<String, Object>> actual = KafkaUtils.async()
                .topic("topic-async")
                .key("key")
                .partition(1)
                .header("h", new byte[] {1})
                .payload("payload")
                .send();

        assertSame(future, actual);
    }

    @Test
    void headerRejectsBlankNameAndNullValue() {
        KafkaSyncSender sender = KafkaUtils.sync().topic("t").payload("p");
        assertEquals("Kafka header 名称不能为空",
                assertThrows(IllegalArgumentException.class, () -> sender.header(" ", new byte[] {1})).getMessage());
        assertEquals("Kafka header 名称不能为空",
                assertThrows(IllegalArgumentException.class, () -> sender.header(null, new byte[] {1})).getMessage());
        assertEquals("Kafka header 值不能为 null, name=traceId",
                assertThrows(IllegalArgumentException.class, () -> sender.header("traceId", null)).getMessage());
    }

    @Test
    void headersMapRejectsInvalidEntryWithoutPartialApply() {
        Map<String, byte[]> withNullValue = new LinkedHashMap<>();
        withNullValue.put("ok", new byte[] {1});
        withNullValue.put("traceId", null);

        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        KafkaSyncSender sender = KafkaUtils.sync().topic("t").payload("p").header("keep", new byte[] {2});
        assertEquals("Kafka header 值不能为 null, name=traceId",
                assertThrows(IllegalArgumentException.class, () -> sender.headers(withNullValue)).getMessage());

        sender.send();
        verify(kafkaTemplate).send(captor.capture());
        assertTrue(captor.getValue().headers().headers("keep").iterator().hasNext());
        assertFalse(captor.getValue().headers().headers("ok").iterator().hasNext());
        assertFalse(captor.getValue().headers().headers("traceId").iterator().hasNext());
    }

    @Test
    void headersIterableRejectsInvalidHeader() {
        KafkaSyncSender sender = KafkaUtils.sync().topic("t").payload("p");
        assertEquals("Kafka header 名称不能为空",
                assertThrows(IllegalArgumentException.class,
                        () -> sender.headers(List.of(new RecordHeader("", new byte[] {1})))).getMessage());
        assertEquals("Kafka header 值不能为 null, name=traceId",
                assertThrows(IllegalArgumentException.class,
                        () -> sender.headers(List.of(new RecordHeader("traceId", null)))).getMessage());
        assertEquals("Kafka header 不能为 null",
                assertThrows(IllegalArgumentException.class,
                        () -> sender.headers(Arrays.asList(new RecordHeader("ok", new byte[] {1}), null))).getMessage());
    }

    @Test
    void headersIterableLandsOnRecordAfterMap() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        KafkaUtils.sync().topic("t").payload("p")
                .header("traceId", "1".getBytes(StandardCharsets.UTF_8))
                .headers(List.of(new RecordHeader("traceId", "2".getBytes(StandardCharsets.UTF_8))))
                .send();

        verify(kafkaTemplate).send(captor.capture());
        List<String> values = new ArrayList<>();
        captor.getValue().headers().headers("traceId")
                .forEach(header -> values.add(new String(header.value(), StandardCharsets.UTF_8)));
        assertEquals(List.of("1", "2"), values);
    }

    @Test
    void validationFailureDoesNotTerminateSender() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        KafkaSyncSender sender = KafkaUtils.sync().payload("only-payload");
        assertThrows(IllegalArgumentException.class, sender::send);

        SendResult<String, Object> actual = sender.topic("t").send();
        assertSame(sendResult, actual);
    }

    @Test
    void sendTerminatesSender() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        KafkaSyncSender sender = KafkaUtils.sync().topic("t").payload("p");
        sender.send();

        assertThrows(IllegalStateException.class, () -> sender.payload("x"));
        assertThrows(IllegalStateException.class, sender::send);
    }

    @Test
    void syncSendInterruptedRestoresInterruptFlag() throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        Thread worker = new Thread(() -> {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> KafkaUtils.sync().topic("topic-interrupt").payload("payload").send());
            assertTrue(ex.getMessage().contains("被中断"));
            assertTrue(ex.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(200);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
    }

    @Test
    void awaitSendInterruptedRestoresInterruptFlag() throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        ProducerRecord<String, Object> record = new ProducerRecord<>("t", "v");

        Thread worker = new Thread(() -> {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> KafkaProducerRecords.awaitSend(kafkaTemplate, record, "t", 60L, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(200);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
    }

    @Test
    void awaitSendDelegatesToTemplate() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));
        ProducerRecord<String, Object> record = new ProducerRecord<>("t", "v");

        SendResult<String, Object> actual =
                KafkaProducerRecords.awaitSend(kafkaTemplate, record, "t", 60L, TimeUnit.SECONDS);

        assertSame(sendResult, actual);
    }

}
