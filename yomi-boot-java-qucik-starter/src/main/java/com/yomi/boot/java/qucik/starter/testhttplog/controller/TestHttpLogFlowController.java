package com.yomi.boot.java.qucik.starter.testhttplog.controller;

import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.httplog.IHttpLogService;
import com.yomi.boot.java.component.httplog.model.HttpLogModel;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * HTTP 日志接入样板：POST 触发一条请求日志（头/体/响应全采 + 脱敏）；查询侧统一经组件服务接口
 * {@link IHttpLogService}（query 概要分页 / detail 全量单条），不直连 Mapper/仓储
 * <p>路径 {@code /test/ds/**} 已在 token/rbac 放行列表中；组件表由启动期自动创建（脚本 sql/mysql|pgsql/sys_http_log.sql）。
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-httplog")
@RequiredArgsConstructor
public class TestHttpLogFlowController {

    /** 业务接入方式：注入组件查询服务接口 */
    private final IHttpLogService httpLogService;

    /**
     * 触发日志采集：请求头 Authorization、请求体 password/mobile/employeeNo 均应按配置脱敏（EMPLOYEE_NO 为自定义策略）
     */
    @PostMapping("/flow")
    public Response<Map<String, Object>> flow(@RequestBody Map<String, Object> body,
                                              @RequestParam("marker") String marker,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("[HTTP日志流程] marker={}, authorization-present={}", marker, authorization != null);
        Map<String, Object> data = new LinkedHashMap<>();
        // 响应体回显敏感字段：token（ALL）与请求参数（递归字段级脱敏）
        data.put("token", "tok-demo-123456");
        data.put("echo", body);
        return Response.success(data);
    }

    /**
     * 异步请求样板：响应体在 ASYNC 分发写完，日志在该分发结束时采集（状态/耗时/响应体均为最终值）
     */
    @GetMapping("/async-flow")
    public Callable<Response<Map<String, Object>>> asyncFlow(@RequestParam("marker") String marker,
                                                             @RequestParam(value = "delayMs", defaultValue = "80")
                                                             long delayMs) {
        return () -> {
            Thread.sleep(delayMs);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("marker", marker);
            data.put("token", "tok-async-123456");
            return Response.success(data);
        };
    }

    /**
     * 业务接入样板：按条件分页查询（概要字段，不含头/体；详情用 {@link #detail(Long)} 或自行 {@code get(id)}）
     * <p>{@link HttpLogQuery} 可直接作为查询参数对象绑定（分页/时间范围/状态/uriLike 等）。
     */
    @GetMapping("/query")
    public PageResponse<HttpLogModel> query(HttpLogQuery query) {
        return httpLogService.page(query);
    }

    /** 业务接入样板：全量单条（含请求/响应头与体，内容为入库时已脱敏值） */
    @GetMapping("/detail")
    public Response<HttpLogModel> detail(@RequestParam("id") Long id) {
        return Response.success(httpLogService.get(id));
    }

}
