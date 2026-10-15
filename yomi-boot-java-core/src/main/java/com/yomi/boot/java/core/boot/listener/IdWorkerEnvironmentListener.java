package com.yomi.boot.java.core.boot.listener;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.core.boot.id.IdProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 雪花机器号环境监听器
 * 在yml、系统属性、环境变量合并完成后，读取配置并初始化雪花机器号，
 * 保证任何业务代码生成ID之前机器号已就绪。
 * <p>
 * 配置键 {@code yomi.id.worker-id}（datacenter 同理），三种来源等价：
 * yml配置、-D系统属性、环境变量YOMI_ID_WORKER_ID。
 * 未配置时不阻断启动：首次生成 ID 时须配置 worker-id，或开启 {@code yomi.id.allow-host-suffix} 后按主机名后缀识别。
 * {@code allow-host-suffix} 的绑定值随调用显式传入（不写进程级系统属性，避免同 JVM 内后续上下文被前一个上下文的状态污染）。
 *
 * @author zwei
 */
@Slf4j
public class IdWorkerEnvironmentListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /** 环境就绪后初始化雪花 IdWorker */
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        IdProperties idProperties = Binder.get(environment)
                .bind("yomi.id", Bindable.of(IdProperties.class))
                .orElseGet(IdProperties::new);
        IdUtils.initialize(idProperties.getWorkerId(), idProperties.getDatacenterId(),
                idProperties.getAllowHostSuffix());
        if (idProperties.getWorkerId() == null) {
            log.warn("雪花机器号未配置（yomi.id.worker-id），首次生成 ID 将失败；StatefulSet 等场景可配置 worker-id 或设置 yomi.id.allow-host-suffix=true");
        }
    }

}
