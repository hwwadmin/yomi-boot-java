package com.yomi.boot.java.core.boot.listener;

import com.yomi.boot.java.base.exception.UtilsException;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.core.boot.id.IdProperties;
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
 * {@code worker-id} 必须配置且无自动推导，缺失即启动失败。
 *
 * @author zwei
 */
public class IdWorkerEnvironmentListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /** 环境就绪后初始化雪花机器号；未配置 worker-id 直接终止启动 */
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        IdProperties idProperties = Binder.get(environment)
                .bind("yomi.id", Bindable.of(IdProperties.class))
                .orElseGet(IdProperties::new);
        Long workerId = idProperties.getWorkerId();
        if (workerId == null) {
            throw UtilsException.exception("""
                    雪花机器号未配置：必须配置 yomi.id.worker-id（0~31，多实例部署时互不相同）。
                    三种来源等价：yml 配置 / 系统属性 -Dyomi.id.worker-id / 环境变量 YOMI_ID_WORKER_ID""");
        }
        IdUtils.initialize(workerId, idProperties.getDatacenterId() != null ? idProperties.getDatacenterId() : 0L);
    }

}
