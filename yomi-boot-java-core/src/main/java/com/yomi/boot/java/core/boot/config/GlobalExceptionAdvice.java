package com.yomi.boot.java.core.boot.config;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.exception.*;
import com.yomi.boot.java.base.model.Response;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 全局异常处理
 *
 * @author zwei
 */
@Slf4j
@RestControllerAdvice
@Order
public class GlobalExceptionAdvice {

    /** 业务异常：HTTP 200，body 为失败信封 */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> bizException(BizException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return Response.fail(ex.getCode(), ex.getErrorCode(), ex.getMessage());
    }

    /** 框架/工具类受控异常 */
    @ExceptionHandler({AssertException.class, UtilsException.class, NotImplException.class})
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> frameworkException(AbstractRuntimeException ex) {
        log.error("系统异常: {}", ex.getMessage(), ex);
        return Response.fail(ex.getCode(), null, ex.getMessage());
    }

    /** {@code @Valid} 请求体/表单绑定校验失败 */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class
    })
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> bindException(Exception ex) {
        BindingResult bindingResult = ex instanceof MethodArgumentNotValidException methodEx
                ? methodEx.getBindingResult()
                : ((BindException) ex).getBindingResult();
        return parameterFail(bindingMessage(bindingResult));
    }

    /** 方法级/参数级 {@code @Validated} 约束违反 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> constraintViolationException(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(GlobalExceptionAdvice::violationMessage)
                .collect(Collectors.joining("; "));
        return parameterFail(message);
    }

    /** 非法参数、类型不匹配、JSON 不可读、缺少绑定参数等 */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> badRequest(Exception ex) {
        log.warn("请求参数错误: {}", ex.getMessage());
        String message = StringUtils.defaultIfBlank(ex.getMessage(), "请求参数错误");
        return parameterFail(message);
    }

    /**
     * 方法参数上的约束校验失败（{@code @RequestParam @Min(5)}、{@code @Xss} 等）：
     * 处理 {@link HandlerMethodValidationException}（如 {@code @RequestParam @Min}、{@code @Xss}）
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> methodValidationException(HandlerMethodValidationException ex) {
        log.warn("请求参数校验失败: {}", ex.getMessage());
        String message = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining("; "));
        return parameterFail(StringUtils.defaultIfBlank(message, "请求参数错误"));
    }

    /** HTTP 方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> methodNotAllowed() {
        return statusFail(StatusCode.methodNotAllowed, "请求方式不支持");
    }

    /** Content-Type 不支持 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> unsupportedMediaType() {
        return statusFail(StatusCode.unsupportedMediaType, "不支持的媒体类型");
    }

    /** 无匹配 Controller 路由 */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> notFound() {
        return statusFail(StatusCode.notFound, "接口不存在");
    }

    /** 未分类异常兜底 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> unknownException(Exception ex) {
        log.error("未处理异常", ex);
        return statusFail(StatusCode.unknown, "服务器繁忙，请稍后重试");
    }

    /** 构造参数错误响应（HTTP 200 + 业务失败码） */
    protected static Response<Void> parameterFail(String message) {
        return Response.fail(StatusCode.parameterError.getCode(), null, message);
    }

    /** 按 {@link StatusCode} 构造失败响应 */
    protected static Response<Void> statusFail(StatusCode status, String message) {
        return Response.fail(status.getCode(), null, message);
    }

    /**
     * 校验错误文案：字段错误带 {@code [字段]} 前缀，类级（global）错误没有字段可指，直接取消息文本
     */
    protected static String bindingMessage(BindingResult bindingResult) {
        return Stream.concat(
                        bindingResult.getFieldErrors().stream().map(GlobalExceptionAdvice::fieldErrorMessage),
                        bindingResult.getGlobalErrors().stream().map(ObjectError::getDefaultMessage))
                .collect(Collectors.joining("; "));
    }

    /** 单字段校验错误文案 */
    protected static String fieldErrorMessage(FieldError fieldError) {
        return String.format("[%s] %s", fieldError.getField(), fieldError.getDefaultMessage());
    }

    /** 方法参数约束违反文案 */
    protected static String violationMessage(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        String field = dot >= 0 ? path.substring(dot + 1) : path;
        return String.format("[%s] %s", field, violation.getMessage());
    }

}
