package com.imyvm.finance;

import net.minecraft.network.chat.Component;

public final class Translator {
    private Translator() {
    }

    public static Component tr(String key, Object... args) {
        return Component.translatable(ImyvmFinance.MOD_ID + "." + key, args);
    }
}
