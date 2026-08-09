package com.imyvm.finance.transaction;

import com.imyvm.finance.market.Instrument;

import java.util.UUID;

public record StockTransaction(
    UUID transactionId,
    UUID playerId,
    StockOperation operation,
    String referenceId,
    Instrument instrument,
    long amount,
    StockTransactionState state,
    String economyResult,
    long createdAtEpochMillis,
    long updatedAtEpochMillis
) {
    public StockTransaction {
        if (transactionId == null || playerId == null)
            throw new IllegalArgumentException("transaction and player ids are required");
        if (operation == null || instrument == null || state == null)
            throw new IllegalArgumentException("transaction types are required");
        if (referenceId == null || referenceId.isBlank())
            throw new IllegalArgumentException("referenceId is required");
        if (amount <= 0)
            throw new IllegalArgumentException("amount must be positive");
    }
}
