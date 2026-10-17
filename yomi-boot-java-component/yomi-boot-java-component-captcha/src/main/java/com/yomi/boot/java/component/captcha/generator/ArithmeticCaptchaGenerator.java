package com.yomi.boot.java.component.captcha.generator;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import com.yomi.boot.java.component.captcha.core.CaptchaRandoms;
import com.yomi.boot.java.component.captcha.core.CaptchaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 看图算数：从预枚举的合法表达式里随机取一条，答案即算式结果
 * <p>
 * 合法表达式在构造期枚举（结果非负、排除 0/1、不超过 {@code max-result}），生成时直接抽取；
 * 数量过少（答案空间小、易被穷举）时启动失败。
 *
 * @author zwei
 */
public class ArithmeticCaptchaGenerator implements CaptchaGenerator {

    /** 合法表达式数量下限：过少会导致答案空间小、易被穷举 */
    private static final int MIN_CANDIDATES = 50;

    /** 构造期枚举的合法算式池，生成时均匀随机抽取 */
    private final List<Expression> candidates;

    public ArithmeticCaptchaGenerator(CaptchaProperties.Arithmetic properties) {
        this.candidates = enumerate(properties);
        if (candidates.size() < MIN_CANDIDATES) {
            throw new IllegalStateException("算术验证码合法表达式仅 " + candidates.size() + " 个（需 ≥" + MIN_CANDIDATES
                    + "）：请调大 yomi.captcha.arithmetic.max-result（当前 " + properties.getMaxResult()
                    + "）或放宽 yomi.captcha.arithmetic.operand-min/operand-max（当前 "
                    + properties.getOperandMin() + "~" + properties.getOperandMax() + "）");
        }
    }

    /** 算术验证码类型 */
    @Override
    public CaptchaType type() {
        return CaptchaType.ARITHMETIC;
    }

    /** 随机取一条算式，展示文本带 {@code =?}，答案为计算结果字符串 */
    @Override
    public CaptchaContent generate() {
        Expression expression = candidates.get(CaptchaRandoms.nextInt(candidates.size()));
        String displayText = expression.left() + expression.operator() + expression.right() + "=?";
        return new CaptchaContent(displayText, String.valueOf(expression.result()));
    }

    /**
     * 枚举全部合法表达式（减法为负、结果 0/1 的组合都被过滤）
     */
    static List<Expression> enumerate(CaptchaProperties.Arithmetic properties) {
        List<Expression> list = new ArrayList<>();
        for (String operator : new LinkedHashSet<>(properties.getOperators())) {
            for (int left = properties.getOperandMin(); left <= properties.getOperandMax(); left++) {
                for (int right = properties.getOperandMin(); right <= properties.getOperandMax(); right++) {
                    int result = switch (operator) {
                        case "+" -> left + right;
                        case "-" -> left - right;
                        case "×" -> left * right;
                        default -> throw new IllegalStateException("不支持的运算符: " + operator
                                + "（取值 " + CaptchaProperties.Arithmetic.SUPPORTED_OPERATORS + "）");
                    };
                    if (result >= 2 && result <= properties.getMaxResult()) {
                        list.add(new Expression(left, operator, right, result));
                    }
                }
            }
        }
        return list;
    }

    /**
     * 一条合法表达式
     */
    record Expression(int left, String operator, int right, int result) {
    }

}
