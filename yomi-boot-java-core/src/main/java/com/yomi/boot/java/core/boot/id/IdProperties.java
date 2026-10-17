package com.yomi.boot.java.core.boot.id;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 雪花 ID 配置，对应 yml 前缀 {@code yomi.id}
 * <p>
 * 由 {@code CoreAutoConfiguration} 的 {@code @EnableConfigurationProperties} 注册（非组件扫描），
 * 机器号在 {@link com.yomi.boot.java.core.boot.listener.IdWorkerEnvironmentListener} 中尽早绑定并初始化；
 * 环境变量 {@code YOMI_ID_WORKER_ID}、{@code YOMI_ID_DATACENTER_ID} 与 yml 键同样可用。
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.id")
public class IdProperties {

    /**
     * 雪花 workerId，取值 0~31；必须配置，多实例部署时须互不相同。
     */
    private Long workerId;

    /**
     * 雪花 datacenterId，取值 0~31；未配置默认 0。
     */
    private Long datacenterId;

}
