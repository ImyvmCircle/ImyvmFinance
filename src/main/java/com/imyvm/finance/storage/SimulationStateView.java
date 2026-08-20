package com.imyvm.finance.storage;

public record SimulationStateView(long sessionId, String symbol, double trendState, int iteration) {
}
