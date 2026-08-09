package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.trading.StockTradeState;
import com.imyvm.finance.trading.TradeSide;

import java.util.UUID;

public record StoredTrade(
    UUID tradeId,
    UUID orderId,
    UUID playerId,
    Instrument instrument,
    TradeSide side,
    long units,
    long executionPriceScaled,
    long grossAmount,
    long feeAmount,
    long settlementAmount,
    String snapshotId,
    StockTradeState state,
    long createdAtEpochMillis
) {
}
