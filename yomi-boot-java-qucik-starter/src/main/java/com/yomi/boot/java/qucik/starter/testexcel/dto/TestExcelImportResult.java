package com.yomi.boot.java.qucik.starter.testexcel.dto;

import java.util.List;

/**
 * 导入结果（业务自行定义，组件不掺和）
 *
 * @param total        总行数（成功 + 失败）
 * @param successCount 成功行数
 * @param failures     失败行明细
 * @author zwei
 */
public record TestExcelImportResult(int total, int successCount, List<TestExcelImportRowError> failures) {
}
