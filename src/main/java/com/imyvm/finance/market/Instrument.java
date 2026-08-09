package com.imyvm.finance.market;

public enum Instrument {
    CN_000001("CN:000001", "CN"),
    CN_399001("CN:399001", "CN"),
    CN_399006("CN:399006", "CN"),
    CN_000300("CN:000300", "CN"),
    CN_000905("CN:000905", "CN"),
    HK_HSI("HK:HSI", "HK"),
    HK_HSTECH("HK:HSTECH", "HK"),
    US_DJI("US:DJI", "US"),
    US_SPX("US:SPX", "US"),
    US_NDX("US:NDX", "US"),
    JP_N225("JP:N225", "JP"),
    KR_KOSPI("KR:KOSPI", "KR");

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

    public static Instrument fromSymbol(String symbol) {
        for (Instrument instrument : values()) {
            if (instrument.symbol.equalsIgnoreCase(symbol))
                return instrument;
        }
        return null;
    }
}
