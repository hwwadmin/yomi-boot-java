package com.yomi.boot.java.base.enums;

import lombok.Getter;

/**
 * 日期时间格式模式（{@link java.text.SimpleDateFormat} / Jackson 默认日期格式等）。
 *
 * @author zwei
 */
@Getter
public enum DateFormat {

    /** 年：yyyy */
    Y("yyyy"),
    /** 月：MM */
    M("MM"),
    /** 日：dd */
    D("dd"),
    /** 时：HH */
    H("HH"),
    /** 分：mm */
    m("mm"),
    /** yyMM */
    YYMM("yyMM"),
    /** yyMMdd */
    YYMMDD("yyMMdd"),
    /** HH:mm:ss */
    HHMMSS("HH:mm:ss"),
    /** yyyy */
    YYYY("yyyy"),
    /** yyyyMM */
    YYYYMM("yyyyMM"),
    /** yyyyMMdd */
    YYYYMMDD("yyyyMMdd"),
    /** yyyyMMdd HH */
    YYYYMMDDHH("yyyyMMdd HH"),
    /** yyyyMMdd HH:mm */
    YYYYMMDDHHMM("yyyyMMdd HH:mm"),
    /** yyyyMMdd HH:mm:ss */
    YYYYMMDDHHMMSS("yyyyMMdd HH:mm:ss"),
    /** yyyyMMdd HH:mm:ss:SSS */
    YYYYMMDDHHMMSSS("yyyyMMdd HH:mm:ss:SSS"),
    /** yyyy-MM */
    YYYYVMM("yyyy-MM"),
    /** yyyy-MM-dd */
    YYYYVMMVDD("yyyy-MM-dd"),
    /** yyyy-MM-dd HH */
    YYYYVMMVDDHH("yyyy-MM-dd HH"),
    /** yyyy-MM-dd HH:mm */
    YYYYVMMVDDHHMM("yyyy-MM-dd HH:mm"),
    /** yyyy-MM-dd HH:mm:ss */
    YYYYVMMVDDHHSS("yyyy-MM-dd HH:mm:ss"),
    /** yyyy-MM-dd HH:mm:ss:SSS */
    YYYYVMMVDDHHSSS("yyyy-MM-dd HH:mm:ss:SSS"),
    /** yyyy/MM/dd */
    YYYYLMMLDD("yyyy/MM/dd"),
    /** yyyy/MM/dd HH */
    YYYYLMMLDDHH("yyyy/MM/dd HH"),
    /** yyyy/MM/dd HH:mm */
    YYYYLMMLDDHHMM("yyyy/MM/dd HH:mm"),
    /** yyyy/MM/dd HH:mm:ss */
    YYYYLMMLDDHHSS("yyyy/MM/dd HH:mm:ss"),
    /** yyyy/MM/dd HH:mm:ss:SSS */
    YYYYLMMLDDHHSSS("yyyy/MM/dd HH:mm:ss:SSS"),
    /** yyyyMMddHHmmss */
    YMDHMS("yyyyMMddHHmmss"),
    /** yyyyMMddHHmmssSS */
    YMDHMSS("yyyyMMddHHmmssSS"),
    /** yy-MM-dd HH:mm */
    YYVMMVDDHHMM("yy-MM-dd HH:mm"),
    /** dd-MMM-yyyy */
    DDMMMYYYY("dd-MMM-yyyy"),
    /** HH:mm */
    HHMM("HH:mm"),
    /** MMM yyyy */
    MMMYYYY("MMM yyyy"),
    ;

    /** SimpleDateFormat 模式字符串 */
    final String value;

    DateFormat(String value) {
        this.value = value;
    }

}
