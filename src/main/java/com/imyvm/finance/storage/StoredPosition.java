package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.trading.StockOrderState;

import java.util.UUID;

public record StoredPosition(
    UUID positionId,
    UUID playerId,
    Instrument instrument,
    long remainingUnits,
    long frozenUnits,
    long positionValue,
    String buySnapshotId,
    long boughtAtEpochMillis,
    long earliestSellAtEpochMillis,
    StockOrderState state
) {
}
