package com.yomi.boot.java.qucik.starter;

import com.yomi.boot.java.core.boot.AppBoot;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类
 * <p>只扫业务自己的 Mapper 包；各组件的 Mapper 由组件自动配置类自行 {@code @MapperScan}，宿主无需关心依赖 jar
 *
 * @author zwei
 */
@SpringBootApplication
@MapperScan(basePackages = {"com.yomi.boot.java.qucik.starter.**.repository.mapper"})
@EnableScheduling
@EnableAsync
@Slf4j
public class AppStartup {

    static void main(String[] args) {
        AppBoot.run(AppStartup.class, args);
    }

}
