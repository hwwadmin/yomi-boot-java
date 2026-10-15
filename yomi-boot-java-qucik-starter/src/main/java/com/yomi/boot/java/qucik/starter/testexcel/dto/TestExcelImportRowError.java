package com.yomi.boot.java.qucik.starter.testexcel.dto;

/**
 * 导入错误行（逐行回显给前端）
 *
 * @param rowNo   Excel 行号（1 起，含表头）
 * @param field   出错字段/列
 * @param message 原因
 * @author zwei
 */
public record TestExcelImportRowError(int rowNo, String field, String message) {
}
