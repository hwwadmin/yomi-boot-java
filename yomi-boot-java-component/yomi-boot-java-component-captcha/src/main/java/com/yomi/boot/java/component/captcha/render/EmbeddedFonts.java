package com.yomi.boot.java.component.captcha.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内嵌字体：Roboto-Bold v2.138（Apache-2.0，许可全文见 resources/fonts/LICENSE.txt）
 * <p>
 * 内嵌的价值是字形全覆盖（含 ×）与跨环境渲染一致；注意 JDK 在 Linux 上加载自定义字体
 * 仍要求系统存在至少一款已安装字体（实测：空字体目录下 {@code Font.createFont} 直接失败），
 * 精简镜像需安装 fontconfig 与任一字体系。
 *
 * @author zwei
 */
final class EmbeddedFonts {

    /** 内嵌字体 classpath 路径 */
    private static final String RESOURCE = "/fonts/Roboto-Bold.ttf";

    /** 加载后的字体实例 */
    private static final Font FONT = load();

    private EmbeddedFonts() {
    }

    static Font font() {
        return FONT;
    }

    /** 从 classpath 加载 TTF，失败抛 {@link IllegalStateException} */
    private static Font load() {
        try (InputStream in = EmbeddedFonts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("内嵌字体缺失: " + RESOURCE);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            throw new IllegalStateException("内嵌字体加载失败: " + RESOURCE
                    + "（若为精简镜像且无任何系统字体，JDK 同样无法加载自定义字体，请安装 fontconfig 与任一字体包，"
                    + "如 Debian/Ubuntu: apt-get install -y fontconfig fonts-dejavu-core）", e);
        }
    }

}
