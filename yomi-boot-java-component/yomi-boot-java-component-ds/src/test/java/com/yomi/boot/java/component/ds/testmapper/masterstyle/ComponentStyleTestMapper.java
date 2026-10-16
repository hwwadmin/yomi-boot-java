package com.yomi.boot.java.component.ds.testmapper.masterstyle;

import org.apache.ibatis.annotations.Select;

/**
 * 组件形态的 Mapper：{@code @MapperScan} 不写 ref（默认走主库）——本组件与四个 DB 组件的真实形态
 */
public interface ComponentStyleTestMapper {

    @Select("select 1")
    int ping();
}
