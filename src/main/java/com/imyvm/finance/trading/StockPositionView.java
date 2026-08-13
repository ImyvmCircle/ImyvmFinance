package com.imyvm.finance.trading;

import com.imyvm.finance.market.Instrument;

import java.util.UUID;

public record StockPositionView(
    UUID positionId,
    UUID playerId,
    Instrument instrument,
    long remainingUnits,
    long frozenUnits,
    String buySnapshotId,
    long boughtAtEpochMillis,
    long earliestSellAtEpochMillis
) {
    public StockPositionView {
        if (positionId == null || playerId == null || instrument == null)
            throw new IllegalArgumentException("position identity is required");
        if (remainingUnits < 0 || frozenUnits < 0 || frozenUnits > remainingUnits)
            throw new IllegalArgumentException("position units are invalid");
        if (buySnapshotId == null || buySnapshotId.isBlank())
            throw new IllegalArgumentException("buySnapshotId is required");
    }
}
