package com.yomi.boot.java.qucik.starter.testexcel.dto;

import com.yomi.boot.java.qucik.starter.testexcel.converter.TestExcelStatusConverter;
import lombok.Getter;
import lombok.Setter;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.write.style.ColumnWidth;

/**
 * Excel 行模型（导入导出共用一个类，{@code @ExcelProperty} 映射列头）
 *
 * @author zwei
 */
@Getter
@Setter
public class TestExcelRow {

    /** 姓名 */
    @ExcelProperty("姓名")
    @ColumnWidth(16)
    private String name;

    /** 年龄 */
    @ExcelProperty("年龄")
    private Integer age;

    /** 邮箱 */
    @ExcelProperty("邮箱")
    @ColumnWidth(28)
    private String email;

    /** 状态（Excel 里存 启用/禁用 文案，经转换器映射 1/0） */
    @ExcelProperty(value = "状态", converter = TestExcelStatusConverter.class)
    private Integer status;

}
