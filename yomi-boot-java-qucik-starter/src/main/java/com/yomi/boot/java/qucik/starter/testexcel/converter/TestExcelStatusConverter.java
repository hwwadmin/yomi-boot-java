package com.yomi.boot.java.qucik.starter.testexcel.converter;

import org.apache.fesod.sheet.converters.Converter;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.GlobalConfiguration;
import org.apache.fesod.sheet.metadata.data.ReadCellData;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;

/**
 * 状态列转换器样板：库存编码（1/0），Excel 里展示文案（启用/禁用）
 *
 * @author zwei
 */
public class TestExcelStatusConverter implements Converter<Integer> {

    /** 演示 Excel 状态：启用 */
    private static final String ENABLED = "启用";
    /** 演示 Excel 状态：禁用 */
    private static final String DISABLED = "禁用";

    /** 支持的 Java 类型为演示状态枚举 */
    @Override
    public Class<?> supportJavaTypeKey() {
        return Integer.class;
    }

    /** Excel 单元格按字符串读写 */
    @Override
    public CellDataTypeEnum supportExcelTypeKey() {
        return CellDataTypeEnum.STRING;
    }

    @Override
    public Integer convertToJavaData(ReadCellData<?> cellData, ExcelContentProperty contentProperty,
                                     GlobalConfiguration globalConfiguration) {
        return ENABLED.equals(cellData.getStringValue()) ? 1 : 0;
    }

    @Override
    public WriteCellData<?> convertToExcelData(Integer value, ExcelContentProperty contentProperty,
                                               GlobalConfiguration globalConfiguration) {
        return new WriteCellData<>(value != null && value == 1 ? ENABLED : DISABLED);
    }

}
