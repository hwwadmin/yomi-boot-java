package com.yomi.boot.java.core.autoconfig;

import com.yomi.boot.java.core.aop.CostTimeAop;
import com.yomi.boot.java.core.bc.BCryptPasswordEncoder;
import com.yomi.boot.java.core.bc.PasswordEncoder;
import com.yomi.boot.java.core.boot.config.GlobalExceptionAdvice;
import com.yomi.boot.java.core.boot.cors.CorsProperties;
import com.yomi.boot.java.core.boot.id.IdProperties;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 宿主启动类不再扫描 {@code com.yomi}（只扫自己的包）时，core 能力必须全部经自动装配到位；
 * Web JSON 契约（Long → 字符串）在真 Boot 装配下仍然生效，不得被 Boot 默认 Jackson 转换器顶掉。
 */
@SpringBootTest(classes = CoreAutoConfigurationBootTest.HostApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CoreAutoConfigurationBootTest {

    @Autowired
    private WebApplicationContext webContext;

    /** 模拟宿主启动类：包内没有 core 组件，也不扫 com.yomi（同包探针控制器由它扫到，见 CoreAutoConfigurationProbeController） */
    @SpringBootApplication
    static class HostApp {
    }

    @Test
    void coreBeansWiredWithoutScanningComYomi() {
        assertInstanceOf(BCryptPasswordEncoder.class, webContext.getBean(PasswordEncoder.class));
        assertInstanceOf(GlobalExceptionAdvice.class, webContext.getBean(GlobalExceptionAdvice.class));
        assertNotNull(webContext.getBean(CostTimeAop.class));
        assertNotNull(webContext.getBean(SpringUtils.class));
        assertEquals(1, webContext.getBeansOfType(CorsProperties.class).size());
        assertEquals(1, webContext.getBeansOfType(IdProperties.class).size());
    }

    @Test
    void webJsonContractKeptThroughBootConverterPipeline() throws Exception {
        // JsonConfig 的转换器 Bean 生效时 Long 序列化为字符串；被 Boot 默认 Jackson 转换器顶掉时是数字
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        mockMvc.perform(get("/probe/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("1234567890123456789"));
    }

}
