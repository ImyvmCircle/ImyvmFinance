package com.imyvm.finance;

import com.imyvm.hoki.i18n.HokiLanguage;
import com.imyvm.hoki.i18n.HokiTranslator;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.io.InputStream;

public final class Translator {
    private static volatile HokiLanguage language = loadLanguage("zh_cn");

    private Translator() {
    }

    public static Component tr(String key, Object... args) {
        return parseStyleCodes(HokiTranslator.translate(language, ImyvmFinance.MOD_ID + "." + key, args));
    }

    public static void setLanguage(String languageId) {
        language = loadLanguage(languageId);
    }

    private static HokiLanguage loadLanguage(String languageId) {
        String path = HokiLanguage.getResourcePath(ImyvmFinance.MOD_ID, languageId);
        InputStream inputStream = Translator.class.getResourceAsStream(path);
        return HokiLanguage.create(inputStream);
    }

    private static Component parseStyleCodes(Component component) {
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        for (Component sibling : component.getSiblings()) {
            if (sibling.getSiblings().isEmpty() && sibling.getStyle().isEmpty()
                && sibling.getContents() instanceof PlainTextContents contents) {
                style = appendParsed(result, contents.text(), style);
            } else {
                result.append(sibling);
            }
        }
        return result;
    }

    private static Style appendParsed(MutableComponent result, String text, Style style) {
        StringBuilder segment = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char code = text.charAt(index);
            ChatFormatting formatting = code == '§' && index + 1 < text.length()
                ? ChatFormatting.getByCode(text.charAt(index + 1))
                : null;
            if (formatting == null) {
                segment.append(code);
                continue;
            }
            if (segment.length() > 0) {
                result.append(Component.literal(segment.toString()).withStyle(style));
                segment.setLength(0);
            }
            style = style.applyLegacyFormat(formatting);
            index++;
        }
        if (segment.length() > 0)
            result.append(Component.literal(segment.toString()).withStyle(style));
        return style;
    }
}
