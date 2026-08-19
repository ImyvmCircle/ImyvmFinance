package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import net.minecraft.network.chat.Component;

public enum Instrument {
    CN_000001("CN:000001", "CN"),
    CN_399001("CN:399001", "CN"),
    CN_399006("CN:399006", "CN"),
    CN_000300("CN:000300", "CN"),
    CN_000905("CN:000905", "CN"),
    CRYPTO_BTC("CRYPTO:BTCUSDT", "CRYPTO"),
    CRYPTO_ETH("CRYPTO:ETHUSDT", "CRYPTO");

    private final String symbol;
    private final String market;

    Instrument(String symbol, String market) {
        this.symbol = symbol;
        this.market = market;
    }

    public String symbol() {
        return symbol;
    }

    public String market() {
        return market;
    }

    public Component label() {
        return Translator.tr("commands.market.instrument.label",
            Translator.tr("instrument." + name().toLowerCase()), symbol);
    }

    public static Instrument fromSymbol(String symbol) {
        for (Instrument instrument : values()) {
            if (instrument.symbol.equalsIgnoreCase(symbol)
                || instrument.commandForm().equalsIgnoreCase(symbol))
                return instrument;
        }
        return null;
    }

    public String commandForm() {
        return symbol.replace(":", "");
    }
}
