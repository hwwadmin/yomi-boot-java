package com.yomi.boot.java.component.httplog.collect;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 采集过滤器分发语义：async 请求等到 ASYNC 分发结束才采集一次（响应体/状态/耗时是最终值），
 * async 线程经包装 writer 写出的内容随该分发的 flush 落到真实响应（客户端与日志都不丢）；
 * 同步请求行为不变。MockMvc 的 asyncDispatch 与容器一致：同请求/响应、DispatcherType=ASYNC、isAsyncStarted=false
 *
 * @author zwei
 */
class HttpLogFilterTest {

    /** 组件测试无 AppBoot，IdWorkerEnvironmentListener 不注册，需手工初始化雪花 */
    @BeforeAll
    static void initIdWorker() {
        IdUtils.initialize(1L, 0L, null);
    }

    private final HttpLogProperties properties = properties();

    private final CapturingSink sink = new CapturingSink();

    private final MockMvc mockMvc = mockMvc();

    @Test
    void syncRequest_collectedOnceWithBodies() throws Exception {
        MvcResult result = mockMvc.perform(post("/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("sync-ok:{\"a\":1}");
        assertThat(sink.records).hasSize(1);
        HttpLogRecord record = sink.records.get(0);
        assertThat(record.getUri()).isEqualTo("/sync");
        assertThat(record.getRequestBody()).isEqualTo("{\"a\":1}");
        assertThat(record.getResponseBody()).isEqualTo("sync-ok:{\"a\":1}");
        assertThat(record.getStatus()).isEqualTo(200);
        assertThat(record.isAsyncStarted()).isFalse();
    }

    @Test
    void asyncRequest_collectedAtAsyncDispatchWithFinalBodyAndDuration() throws Exception {
        MvcResult result = mockMvc.perform(get("/async").param("delay", "60"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(sink.records).isEmpty();

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        assertThat(sink.records).hasSize(1);
        HttpLogRecord record = sink.records.get(0);
        assertThat(record.isAsyncStarted()).isTrue();
        assertThat(record.getStatus()).isEqualTo(200);
        assertThat(record.getResponseBody()).isEqualTo("async-ok");
        // 耗时覆盖 async 等待，不是"async 起始时的几毫秒"
        assertThat(record.getDurationMs()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void asyncRequest_recordsFinalStatusNotInitial200() throws Exception {
        MvcResult result = mockMvc.perform(get("/async-status"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isServiceUnavailable());

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).getStatus()).isEqualTo(503);
    }

    @Test
    void asyncRequest_writerWrittenOnAsyncThread_reachesClientAndRecord() throws Exception {
        MvcResult result = mockMvc.perform(get("/async-writer"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();

        // 首次分发取到的包装 writer 在 async 线程续写且不 flush：须在 ASYNC 分发结束时落到底层响应
        assertThat(dispatched.getResponse().getContentAsString()).isEqualTo("async-writer-body");
        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).getResponseBody()).isEqualTo("async-writer-body");
    }

    private static HttpLogProperties properties() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setEnabled(true);
        properties.setServiceName("filter-test");
        return properties;
    }

    private MockMvc mockMvc() {
        HttpLogKeys keys = new HttpLogKeys(properties, new MockEnvironment());
        // 空词表：脱敏不生效，本用例只关心采集语义
        HttpLogDesensitizer desensitizer = new HttpLogDesensitizer((type, value) -> value, properties.getDesensitize());
        HttpLogRecordFactory factory = new HttpLogRecordFactory(properties, keys, desensitizer, List.of());
        HttpLogMetrics metrics = new HttpLogMetrics(
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        HttpLogFilter filter = new HttpLogFilter(properties, factory, sink, metrics);
        return MockMvcBuilders.standaloneSetup(new TestController()).addFilters(filter).build();
    }

    /** 采集结果捕获（替代真实传输） */
    private static final class CapturingSink implements HttpLogSink {

        private final List<HttpLogRecord> records = new ArrayList<>();

        @Override
        public boolean offer(HttpLogRecord record) {
            records.add(record);
            return true;
        }

        @Override
        public void flush(Duration timeout) {
        }

    }

    /** 测试端点：同步 / MVC 异步 / 手写异步 */
    @RestController
    private static class TestController {

        /** 同步对照 */
        @PostMapping("/sync")
        public String sync(@RequestBody String body) {
            return "sync-ok:" + body;
        }

        /** MVC 异步：响应体在 ASYNC 分发由消息转换器写出 */
        @GetMapping("/async")
        public Callable<String> async(@RequestParam("delay") long delay) {
            return () -> {
                Thread.sleep(delay);
                return "async-ok";
            };
        }

        /** async 线程改状态：采集若固化在 async 起始会记成 200 */
        @GetMapping("/async-status")
        public Callable<Void> asyncStatus(HttpServletResponse response) {
            return () -> {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return null;
            };
        }

        /** 首次分发取包装 writer，async 线程续写且不 flush */
        @GetMapping("/async-writer")
        public Callable<Void> asyncWriter(HttpServletResponse response) throws IOException {
            PrintWriter writer = response.getWriter();
            return () -> {
                writer.write("async-writer-body");
                return null;
            };
        }

    }

}
