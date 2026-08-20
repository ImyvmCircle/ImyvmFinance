package com.imyvm.finance.market;

public record MarketQuote(
    Instrument instrument,
    String name,
    long priceScaled,
    long changeBps,
    MarketStatus status,
    QuoteOrigin origin
) {
    public MarketQuote(Instrument instrument, String name, long priceScaled, long changeBps, MarketStatus status) {
        this(instrument, name, priceScaled, changeBps, status, QuoteOrigin.REAL);
    }
    public MarketQuote {
        if (instrument == null)
            throw new IllegalArgumentException("instrument is required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name is required");
        if (priceScaled < 0)
            throw new IllegalArgumentException("priceScaled must not be negative");
        if (status == null)
            throw new IllegalArgumentException("status is required");
        if (origin == null)
            throw new IllegalArgumentException("origin is required");
    }
}
