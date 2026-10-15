package com.yomi.boot.java.base.utils;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * xss工具类
 * <p>
 * 检测与清洗共用同一白名单（{@link #SAFELIST}），保证通过 {@link #isXssAttack} 检测的内容
 * 经 {@link #cleanXss} 清洗后保持原样。
 * 检测基于 jsoup 白名单清洗比对实现：将原始 DOM 与按白名单清洗后的 DOM 做全文档比对，
 * 清洗导致内容变化即认为包含不安全内容（script 标签、事件属性、危险协议 URL、白名单外标签等）。
 * 比较发生在规范化后的 DOM 层，纯书写差异（标签大小写、单双引号、多余空格、文本中的
 * {@code &} 转义等）不会误报；构成标签或未闭合标签的裸露 {@code <} 仍会被判为不安全。
 *
 * @author zwei
 */
public abstract class XssUtils {

    private XssUtils() {
    }

    /**
     * 哑 baseUri：仅用于让相对路径 URL（如 src="a.png"）解析出可校验的协议，
     * 不产生任何网络请求；配合 preserveRelativeLinks 输出仍保留相对路径原文
     */
    private static final String LOCAL_BASE_URI = "http://xss.detect.local/";

    /**
     * 统一白名单：relaxed 基础上保留相对路径 URL（使 src="a.png"、href="#top" 等不被误判），
     * 并去掉强制附加的 rel=nofollow 属性（使正常超链接不被误判）
     */
    private static final Safelist SAFELIST = Safelist.relaxed()
            .preserveRelativeLinks(true)
            .removeEnforcedAttribute("a", "rel");

    /**
     * 判断是否包含不安全内容（script 标签、事件属性、javascript:/data: 协议、白名单外标签等）。
     * 普通的结构化标签（div、img、table 等）、相对路径链接和文本中的 {@code &} 不会被判为攻击
     *
     * @param str 待判断的字符串
     * @return 是否为XSS攻击
     */
    public static boolean isXssAttack(String str) {
        if (StringUtils.isBlank(str)) return false;
        Document dirty = Jsoup.parse(str, LOCAL_BASE_URI);
        Document clean = new Cleaner(SAFELIST).clean(Jsoup.parse(str, LOCAL_BASE_URI));
        return !clean.html().equals(dirty.html());
    }

    /**
     * 清理XSS攻击，仅保留与 {@link #isXssAttack} 检测一致的白名单内的标签与属性
     *
     * @param str 待清理的字符串
     * @return 清理后的字符串
     */
    public static String cleanXss(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }
        return Jsoup.clean(str, LOCAL_BASE_URI, SAFELIST);
    }

}
