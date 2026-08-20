package com.imyvm.finance.storage;

public record SimulationNodeInputView(long sessionId, long nodeTime, String symbol, int inputIndex,
                                      String source, long quoteTime, long priceScaled) {
}
