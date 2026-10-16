package com.yomi.boot.java.component.ds.testmapper.reportstyle;

import org.apache.ibatis.annotations.Select;

/**
 * 业务附加库形态的 Mapper：{@code @MapperScan} 显式绑定到业务自建的 reportSqlSessionFactory
 */
public interface ReportTestMapper {

    @Select("select 1")
    int ping();
}
