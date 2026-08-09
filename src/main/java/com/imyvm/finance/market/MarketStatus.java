package com.imyvm.finance.market;

public enum MarketStatus {
    OPEN,
    CLOSED,
    UNAVAILABLE;

    public static MarketStatus parse(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (RuntimeException exception) {
            return UNAVAILABLE;
        }
    }
}
