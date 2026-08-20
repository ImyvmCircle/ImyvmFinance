package com.imyvm.finance.storage;

public record SimulationNodeView(long sessionId, long nodeTime, String symbol, String inputSource, long previousPrice,
                                 long fluctuationBps, long newPrice, double logReturn, int factor) {
}
