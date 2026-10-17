package com.yomi.boot.java.core.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * IOC帮助类
 * 依托SPRING实现基础性的IOC功能
 *
 * @author zwei
 */
@SuppressWarnings("unused")
@Component
@Slf4j
public class SpringUtils implements ApplicationContextAware {

    /** spring上下文 */
    private static volatile ApplicationContext applicationContext;

    /**
     * 根据BEAN的名称获取BEAN
     *
     * @param name bean的名称
     * @return 如果bean不存在会类型不正确，会抛出异常
     */
    public static <T> T getBean(String name) {
        if (!containsBean(name)) {
            throw UtilsException.exception("找不到名称为[{}]的IOC对象定义", name);
        }
        try {
            //noinspection unchecked
            return (T) applicationContext.getBean(name);
        } catch (Exception e) {
            throw UtilsException.exception(e, "名称为[{}]的IOC对象转换错误", name);
        }
    }

    /** 按类型获取唯一 Bean，不存在或类型不匹配时抛异常 */
    public static <T> T getBean(Class<T> clazz) {
        verifyApplicationContext();
        return applicationContext.getBean(clazz);
    }

    /** 按名称与类型获取 Bean */
    public static <T> T getBean(String name, Class<T> clazz) {
        verifyApplicationContext();
        return applicationContext.getBean(name, clazz);
    }

    /** 按类型获取 Bean；上下文未就绪或获取失败时返回 null */
    public static <T> T getBeanNoEx(Class<T> clazz) {
        if (Objects.isNull(applicationContext)) {
            return null;
        }
        try {
            return applicationContext.getBean(clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取该类型的全部 Bean 实例列表 */
    public static <T> List<T> getBean2List(Class<T> clazz) {
        verifyApplicationContext();
        Map<String, T> beans = applicationContext.getBeansOfType(clazz);
        return new ArrayList<>(beans.values());
    }

    /** 获取该类型的全部 Bean，键为 Bean 名称 */
    public static <T> Map<String, T> getBean2Map(Class<T> clazz) {
        verifyApplicationContext();
        return applicationContext.getBeansOfType(clazz);
    }

    /**
     * 获取SPRING的全局配置文件值
     * 如果key为空或key不存在，那么直接返回null
     */
    public static String getConfig(String key) {
        verifyApplicationContext();
        if (StringUtils.isBlank(key)) {
            return null;
        }
        return applicationContext.getEnvironment().getProperty(key);
    }

    /**
     * 根据关键字获取配置
     * 如果key为空或key不存在，那么直接返回指定的默认值
     */
    public static String getConfig(String key, String defaultValue) {
        verifyApplicationContext();
        if (StringUtils.isBlank(key)) {
            return defaultValue;
        }
        if (!applicationContext.getEnvironment().containsProperty(key)) {
            return defaultValue;
        }
        try {
            return applicationContext.getEnvironment().getProperty(key);
        } catch (Throwable e) {
            return defaultValue;
        }
    }

    /**
     * 判断是否存在指定名称的BEAN
     */
    public static boolean containsBean(String name) {
        verifyApplicationContext();
        if (StringUtils.isBlank(name)) {
            throw UtilsException.exception("需要获取的对象名称不能为空");
        }
        return applicationContext.containsBean(name);
    }

    /**
     * 校验上下文是否初始化
     */
    private static void verifyApplicationContext() {
        if (Objects.isNull(applicationContext)) throw UtilsException.exception("上下文未初始化");
    }

    /** 获取已初始化的 Spring 上下文 */
    public static ApplicationContext getApplicationContext() {
        verifyApplicationContext();
        return applicationContext;
    }

    /** 由 Spring 注入并缓存 {@link ApplicationContext} */
    @Override
    public void setApplicationContext(@NonNull ApplicationContext context) {
        log.info("SpringUtils注入spring上下文");
        applicationContext = context;
    }

}
