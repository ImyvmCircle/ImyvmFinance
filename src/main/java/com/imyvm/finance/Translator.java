package com.imyvm.finance;

import com.imyvm.hoki.i18n.HokiLanguage;
import com.imyvm.hoki.i18n.HokiTranslator;
import net.minecraft.network.chat.Component;

import java.io.InputStream;

public final class Translator {
    private static volatile HokiLanguage language = loadLanguage("en_us");

    private Translator() {
    }

    public static Component tr(String key, Object... args) {
        return HokiTranslator.translate(language, ImyvmFinance.MOD_ID + "." + key, args);
    }

    public static void setLanguage(String languageId) {
        language = loadLanguage(languageId);
    }

    private static HokiLanguage loadLanguage(String languageId) {
        String path = HokiLanguage.getResourcePath(ImyvmFinance.MOD_ID, languageId);
        InputStream inputStream = Translator.class.getResourceAsStream(path);
        return HokiLanguage.create(inputStream);
    }
}
