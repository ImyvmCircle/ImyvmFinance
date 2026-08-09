package com.imyvm.finance.trading;

import com.imyvm.finance.market.Instrument;

public record TradeEstimate(
    TradeSide side,
    Instrument instrument,
    long units,
    String snapshotId,
    long executionPriceScaled,
    long grossAmount,
    long feeAmount,
    long settlementAmount,
    int slippageBps,
    int feeBps
) {
}
