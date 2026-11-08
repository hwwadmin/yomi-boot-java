package com.yomi.boot.java.qucik.starter.testtable;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 测试表实体（建表脚本 yomi-boot-java-qucik-starter/sql/mysql|pgsql/test_table.sql）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("test_table")
public class TestTable {

    /** 主键 */
    @TableId
    private Long id;

    /** 名称 */
    private String name;

    /** 年龄 */
    private Integer age;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private Instant createTime;

    /** 创建人ID */
    private Long createBy;

    /** 更新时间 */
    private Instant updateTime;

    /** 更新人ID */
    private Long updateBy;

    /** 删除时间 */
    private Instant deleteTime;

    /** 删除人ID */
    private Long deleteBy;

}
