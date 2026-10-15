package com.yomi.boot.java.core.boot.config;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.core.annotation.Xss;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 方法参数上的约束校验（{@code @RequestParam @Min}、{@code @Xss}）失败必须报 422 参数错误，
 * 而不是落到兜底的"服务器繁忙"（历史上 Spring 6.1+ 的 {@code HandlerMethodValidationException} 无映射）；
 * 类级（global）校验错误与字段错误一样必须出现在错误文案里。
 */
class GlobalExceptionAdviceTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionAdvice())
            .build();

    @Test
    void invalidRequestParamReportsParameterError() throws Exception {
        mockMvc.perform(get("/probe/min").param("n", "1"))
                .andExpect(status().isOk())  // 信封约定：HTTP 200，错误分层走 body 的 code
                .andExpect(jsonPath("$.code").value(StatusCode.parameterError.getCode()));
    }

    @Test
    void xssRequestParamReportsParameterError() throws Exception {
        mockMvc.perform(get("/probe/xss").param("name", "<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(StatusCode.parameterError.getCode()));
    }

    @Test
    void classLevelValidationErrorIsReported() throws Exception {
        // 只有类级约束失败（字段级通过）：历史实现只取 getFieldErrors() → debugMessage 为空
        mockMvc.perform(post("/probe/form").param("password", "aaa").param("confirmPassword", "bbb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(StatusCode.parameterError.getCode()))
                .andExpect(jsonPath("$.debugMessage").value("password-mismatch"));
    }

    @Test
    void fieldAndClassLevelErrorsAreMerged() throws Exception {
        // 字段级 + 类级同时失败：两条文案都要出现
        mockMvc.perform(post("/probe/form").param("password", "").param("confirmPassword", "bbb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugMessage").value("[password] password-blank; password-mismatch"));
    }

    @Test
    void validParamsPass() throws Exception {
        mockMvc.perform(get("/probe/min").param("n", "7"))
                .andExpect(status().isOk())
                .andExpect(content().string("n=7"));
        // 正向用例只用 ASCII 回显：standalone MockMvc 的响应编码是 ISO-8859-1，中文会被写成 '?'（与本用例无关）
        mockMvc.perform(get("/probe/xss").param("name", "normal-text"))
                .andExpect(status().isOk())
                .andExpect(content().string("normal-text"));
        mockMvc.perform(post("/probe/form").param("password", "same").param("confirmPassword", "same"))
                .andExpect(status().isOk())
                .andExpect(content().string("form-ok"));
    }

    /** 探针控制器：参数校验（方法参数约束）与表单校验（字段级 + 类级）两条路径 */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/min")
        String min(@RequestParam @Min(5) int n) {
            return "n=" + n;
        }

        @GetMapping("/probe/xss")
        String xss(@RequestParam @Xss String name) {
            return name;
        }

        @PostMapping("/probe/form")
        String form(@ModelAttribute @Valid ProbeForm form) {
            return "form-ok";
        }
    }

    /** 表单：字段级约束 message 用 ASCII（standalone MockMvc 响应编码为 ISO-8859-1，中文会被写成 '?'） */
    @Data
    @PasswordMatch(message = "password-mismatch")
    static class ProbeForm {

        @NotBlank(message = "password-blank")
        private String password;

        private String confirmPassword;
    }

    /** 类级（对象级）约束：校验失败产生 ObjectError 而非 FieldError */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = PasswordMatchValidator.class)
    public @interface PasswordMatch {

        String message() default "password-mismatch";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class PasswordMatchValidator implements ConstraintValidator<PasswordMatch, ProbeForm> {

        @Override
        public boolean isValid(ProbeForm value, ConstraintValidatorContext context) {
            return Objects.equals(value.getPassword(), value.getConfirmPassword());
        }
    }

}
