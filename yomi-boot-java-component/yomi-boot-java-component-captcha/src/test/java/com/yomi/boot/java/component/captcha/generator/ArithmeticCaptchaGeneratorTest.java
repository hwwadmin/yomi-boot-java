package com.yomi.boot.java.component.captcha.generator;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 看图算数生成器：答案与算式一致、结果范围、运算符覆盖、非法配置启动失败
 */
class ArithmeticCaptchaGeneratorTest {

    @Test
    void answerMatchesExpressionAndRespectsRange() {
        CaptchaProperties.Arithmetic properties = new CaptchaProperties().getArithmetic();
        ArithmeticCaptchaGenerator generator = new ArithmeticCaptchaGenerator(properties);
        Set<String> operators = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            CaptchaContent content = generator.generate();
            String display = content.displayText();
            assertTrue(display.endsWith("=?"), display);
            String body = display.substring(0, display.length() - 2);
            int result = evaluate(body);
            assertEquals(String.valueOf(result), content.answer(), "答案与算式必须一致: " + display);
            assertTrue(result >= 2 && result <= properties.getMaxResult(), "结果越界: " + display + " = " + result);
            operators.add(operatorOf(body));
        }
        assertEquals(Set.of("+", "-", "×"), operators, "默认三种运算符都应出现");
    }

    @Test
    void enumerateFiltersNegativeAndTrivialResults() {
        CaptchaProperties.Arithmetic properties = new CaptchaProperties().getArithmetic();
        List<ArithmeticCaptchaGenerator.Expression> expressions = ArithmeticCaptchaGenerator.enumerate(properties);
        assertFalse(expressions.isEmpty());
        for (ArithmeticCaptchaGenerator.Expression expression : expressions) {
            assertTrue(expression.result() >= 2, "减法为负与结果 0/1 必须被过滤: " + expression);
            assertTrue(expression.result() <= properties.getMaxResult(), "结果超过上限: " + expression);
        }
    }

    @Test
    void tooFewCandidatesFailsFast() {
        CaptchaProperties.Arithmetic properties = new CaptchaProperties().getArithmetic();
        properties.setOperandMin(9);
        properties.setOperandMax(9);
        properties.setMaxResult(4);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ArithmeticCaptchaGenerator(properties));
        assertTrue(error.getMessage().contains("合法表达式"), error.getMessage());
    }

    /** 求值：解析 "12-7"、"3×5"、"3+5" */
    private static int evaluate(String body) {
        for (int i = 1; i < body.length(); i++) {
            char operator = body.charAt(i);
            if (operator == '+' || operator == '-' || operator == '×') {
                int left = Integer.parseInt(body.substring(0, i));
                int right = Integer.parseInt(body.substring(i + 1));
                return switch (operator) {
                    case '+' -> left + right;
                    case '-' -> left - right;
                    default -> left * right;
                };
            }
        }
        throw new IllegalArgumentException("非法算式: " + body);
    }

    /** 取算式中的运算符 */
    private static String operatorOf(String body) {
        for (int i = 1; i < body.length(); i++) {
            char operator = body.charAt(i);
            if (operator == '+' || operator == '-' || operator == '×') {
                return String.valueOf(operator);
            }
        }
        throw new IllegalArgumentException("非法算式: " + body);
    }

}
