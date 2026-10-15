package com.yomi.boot.java.component.event;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 离线单测的 MyBatis-Plus 适配：仓储用 LambdaWrapper 构造 SQL 时列名靠 MP 的实体信息缓存解析，
 * 生产里该缓存由 Mapper 初始化填充；未启动 MyBatis 的单测需先手工注册实体，否则抛
 * {@code can not find lambda cache for this entity}。
 *
 * @author zwei
 */
public final class MybatisLambdaCache {

    private MybatisLambdaCache() {
    }

    /** 注册实体表信息（幂等） */
    public static void register(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

}
