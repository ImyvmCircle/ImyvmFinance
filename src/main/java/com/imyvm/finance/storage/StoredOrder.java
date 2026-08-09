package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.trading.StockOrderState;

import java.util.UUID;

public record StoredOrder(
    UUID orderId,
    UUID playerId,
    UUID transactionId,
    Instrument instrument,
    long units,
    long amount,
    String snapshotId,
    StockOrderState state,
    long createdAtEpochMillis
) {
}
