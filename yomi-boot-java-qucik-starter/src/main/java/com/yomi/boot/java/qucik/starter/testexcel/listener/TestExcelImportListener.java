package com.yomi.boot.java.qucik.starter.testexcel.listener;

import com.yomi.boot.java.qucik.starter.testexcel.dto.TestExcelImportRowError;
import com.yomi.boot.java.qucik.starter.testexcel.dto.TestExcelRow;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.exception.ExcelAnalysisException;
import org.apache.fesod.sheet.exception.ExcelDataConvertException;
import org.apache.fesod.sheet.read.listener.ReadListener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 导入监听器样板：校验失败与单元格转换失败都收进错误明细逐行回显，其余行继续导入
 * <p>
 * 单次读串行回调，非线程安全；演示规模一次性收内存，真实大批量应在 {@code invoke} 里分批落库。
 *
 * @author zwei
 */
public class TestExcelImportListener implements ReadListener<TestExcelRow> {

    /** 单次导入行数上限，防异常文件打爆内存 */
    private static final int MAX_ROWS = 10_000;

    /** 演示 Excel 列：邮箱 */
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

    /** 成功解析的行 */
    private final List<TestExcelRow> rows = new ArrayList<>();
    /** 行级错误信息 */
    private final List<TestExcelImportRowError> errors = new ArrayList<>();

    /** 逐行校验并收集 */
    @Override
    public void invoke(TestExcelRow row, AnalysisContext context) {
        int rowNo = context.readRowHolder().getRowIndex() + 1;
        if (rows.size() + errors.size() >= MAX_ROWS) {
            throw new ExcelAnalysisException("单次导入超过 " + MAX_ROWS + " 行上限");
        }
        List<String> problems = new ArrayList<>();
        if (StringUtils.isBlank(row.getName())) {
            problems.add("姓名不能为空");
        }
        if (row.getAge() == null || row.getAge() < 16 || row.getAge() > 70) {
            problems.add("年龄须在 16-70 之间");
        }
        if (StringUtils.isBlank(row.getEmail()) || !EMAIL.matcher(row.getEmail()).matches()) {
            problems.add("邮箱格式不正确");
        }
        if (!problems.isEmpty()) {
            errors.add(new TestExcelImportRowError(rowNo, "校验", String.join("；", problems)));
            return;
        }
        rows.add(row);
    }

    /** 解析异常时记录行号与原因 */
    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
        // 年龄列填了文字这类转换失败：记一行错误后继续读
        if (exception instanceof ExcelDataConvertException convert) {
            errors.add(new TestExcelImportRowError(convert.getRowIndex() + 1,
                    "第" + (convert.getColumnIndex() + 1) + "列", "单元格数据无法转换"));
            return;
        }
        if (exception instanceof ExcelAnalysisException analysis) {
            throw analysis;
        }
        throw new ExcelAnalysisException(exception);
    }

    /** 全部 sheet 解析结束回调 */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 演示为内存收集，无需收尾
    }

    /** 返回已收集的有效行 */
    public List<TestExcelRow> rows() {
        return rows;
    }

    /** 返回导入过程错误列表 */
    public List<TestExcelImportRowError> errors() {
        return errors;
    }

}
