package com.imyvm.finance.market;

import java.util.List;

public record QuoteSnapshot(
    String snapshotId,
    String source,
    long fetchedAtEpochMillis,
    long marketTimeEpochMillis,
    List<MarketQuote> quotes
) {
    public QuoteSnapshot {
        if (snapshotId == null || snapshotId.isBlank())
            throw new IllegalArgumentException("snapshotId is required");
        if (source == null || source.isBlank())
            throw new IllegalArgumentException("source is required");
        if (quotes == null || quotes.isEmpty())
            throw new IllegalArgumentException("quotes are required");
        quotes = List.copyOf(quotes);
    }
}
