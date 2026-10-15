package com.yomi.boot.java.core.boot;

import com.yomi.boot.java.base.utils.TimeZoneUtils;
import com.yomi.boot.java.core.boot.listener.ApplicationFailedEventListener;
import com.yomi.boot.java.core.boot.listener.ContextClosedEventListener;
import com.yomi.boot.java.core.boot.listener.IdWorkerEnvironmentListener;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;

import java.security.Security;
import java.util.TimeZone;

/**
 * 系统启动引导
 * <p>
 * 运行时编码（文件名/字符集）须在 JVM 启动参数中指定，如
 * {@code -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8}；这两个属性在 JVM 初始化期读取，启动后设置无效。
 *
 * @author zwei
 */
@Slf4j
public abstract class AppBoot {

    /**
     * 启动
     */
    public static void run(Class<?> mainClass, String[] args) {
        long startTime = System.currentTimeMillis();
        var timeZone = TimeZoneUtils.timeZone();
        boot(timeZone, mainClass).run(args);
        log.info("系统启动完成，耗时：{} 秒.", (System.currentTimeMillis() - startTime) / 1000);
        log.info("系统时区：{} ({})", timeZone.getID(), timeZone.getDisplayName());
    }

    /**
     * 创建spring对象
     */
    public static SpringApplication boot(TimeZone timeZone, Class<?>... primarySources) {
        // 添加BouncyCastleProvider加密算法
        Security.addProvider(new BouncyCastleProvider());
        // 设置时区
        TimeZone.setDefault(timeZone);

        // springboot
        SpringApplication springApplication = new SpringApplication(primarySources);
        springApplication.addListeners(new IdWorkerEnvironmentListener(),
                new ApplicationFailedEventListener(), new ContextClosedEventListener());

        return springApplication;
    }

}
