package com.imyvm.finance.market;

public record MarketQuote(
    Instrument instrument,
    String name,
    long priceScaled,
    long changeBps,
    MarketStatus status
) {
    public MarketQuote {
        if (instrument == null)
            throw new IllegalArgumentException("instrument is required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name is required");
        if (priceScaled < 0)
            throw new IllegalArgumentException("priceScaled must not be negative");
        if (status == null)
            throw new IllegalArgumentException("status is required");
    }
}
