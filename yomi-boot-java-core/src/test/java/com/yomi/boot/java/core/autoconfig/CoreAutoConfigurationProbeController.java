package com.yomi.boot.java.core.autoconfig;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 探针控制器（顶层类，供宿主启动类扫描）：验证真 Boot 装配下的 Web JSON 契约
 *
 * @author zwei
 */
@RestController
class CoreAutoConfigurationProbeController {

    /** Long 必须是 JSON 字符串（JsonConfig 的转换器生效），数字说明被默认 Jackson 转换器顶掉 */
    @GetMapping(value = "/probe/json", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> json() {
        return Map.of("id", 1234567890123456789L);
    }

}
