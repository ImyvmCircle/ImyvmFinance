package com.imyvm.finance.economy;

import com.imyvm.finance.transaction.StockTransactionState;

public record EconomySettlementResult(
    StockTransactionState state,
    long amount
) {
    public boolean isConfirmed() {
        return state == StockTransactionState.ECONOMY_CONFIRMED;
    }
}
