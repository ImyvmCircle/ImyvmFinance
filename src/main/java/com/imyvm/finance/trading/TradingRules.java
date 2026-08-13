package com.imyvm.finance.trading;

public record TradingRules(
    long maxQuoteAgeMillis,
    long sellCooldownMillis,
    int feeBps,
    int baseSlippageBps,
    long dailyBuyLimit,
    long dailySellLimit,
    long positionValueLimit,
    long minUnits
) {
    public static final TradingRules DEFAULT = new TradingRules(
        15L * 60 * 1000,
        30L * 60 * 1000,
        20,
        10,
        100_000,
        100_000,
        300_000,
        1);
}
