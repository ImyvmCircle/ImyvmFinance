package com.imyvm.finance.storage;

import com.imyvm.finance.market.MarketQuote;

public record StoredQuote(
    String snapshotId,
    String source,
    long fetchedAtEpochMillis,
    long marketTimeEpochMillis,
    long nodeTimeEpochMillis,
    MarketQuote quote
) {
    public StoredQuote(String snapshotId, String source, long fetchedAtEpochMillis,
                       long marketTimeEpochMillis, MarketQuote quote) {
        this(snapshotId, source, fetchedAtEpochMillis, marketTimeEpochMillis, marketTimeEpochMillis, quote);
    }
}
