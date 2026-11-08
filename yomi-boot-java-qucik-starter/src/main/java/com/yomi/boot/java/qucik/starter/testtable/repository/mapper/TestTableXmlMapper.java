package com.yomi.boot.java.qucik.starter.testtable.repository.mapper;

import com.yomi.boot.java.qucik.starter.testtable.TestTable;
import org.apache.ibatis.annotations.Param;

/**
 * 测试表 mapper.xml 形式 mapper：全部 SQL 手写在 resources/mapper/TestTableXmlMapper.xml，
 * 方法签名与 XML 中的语句一一对应
 *
 * @author zwei
 */
public interface TestTableXmlMapper {

    /**
     * 新增
     */
    int insertTestTable(TestTable entity);

    /**
     * 按ID查询（逻辑删除的行不可见）
     */
    TestTable selectTestTableById(@Param("id") Long id);

    /**
     * 按ID更新名称/年龄/备注与更新时间
     */
    int updateTestTable(TestTable entity);

    /**
     * 按ID物理删除
     */
    int deleteTestTableById(@Param("id") Long id);

}
