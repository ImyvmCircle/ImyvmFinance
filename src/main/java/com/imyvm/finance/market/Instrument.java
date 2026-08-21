package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import net.minecraft.network.chat.Component;

public enum Instrument {
    CN_000001("CN:000001", "CN", "CN"),
    CN_399001("CN:399001", "CN", "CN"),
    CN_399006("CN:399006", "CN", "CN"),
    CN_000300("CN:000300", "CN", "CN"),
    CN_000905("CN:000905", "CN", "CN"),
    GOLD_518880("GOLD:518880", "GOLD", "CN"),
    BOND_511090("BOND:511090", "BOND", "CN"),
    FUTURES_159980("FUTURES:159980", "FUTURES", "CN"),
    CRYPTO_BTC("CRYPTO:BTCUSDT", "CRYPTO", "CRYPTO"),
    CRYPTO_ETH("CRYPTO:ETHUSDT", "CRYPTO", "CRYPTO");

    private final String symbol;
    private final String market;
    private final String sourceMarket;

    Instrument(String symbol, String market, String sourceMarket) {
        this.symbol = symbol;
        this.market = market;
        this.sourceMarket = sourceMarket;
    }

    public String symbol() {
        return symbol;
    }

    public String market() {
        return market;
    }

    public String sourceMarket() {
        return sourceMarket;
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

    public static java.util.List<String> markets() {
        return java.util.Arrays.stream(values()).map(Instrument::market).distinct().toList();
    }

    public static java.util.List<String> sourceMarkets() {
        return java.util.Arrays.stream(values()).map(Instrument::sourceMarket).distinct().toList();
    }

    public static String sourceMarket(String market) {
        return java.util.Arrays.stream(values()).filter(instrument -> instrument.market.equals(market))
            .map(Instrument::sourceMarket).findFirst().orElse(market);
    }

    public String commandForm() {
        return symbol.replace(":", "");
    }
}
