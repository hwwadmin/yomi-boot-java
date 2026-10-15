package com.yomi.boot.java.qucik.starter.testtable;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 测试表实体（建表脚本 sql/test_table.sql）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("test_table")
public class TestTable extends BaseEntity {

    /** 名称 */
    private String name;

    /** 年龄 */
    private Integer age;

    /** 备注 */
    private String remark;

}
