package com.imyvm.finance.storage;

public record SimulationSessionView(long sessionId, String sessionUuid, String market, long startedAt, Long endedAt,
                                    String functionId, String formula, long seed, long intervalMillis,
                                    long intervalToleranceMillis, int factor, String status) {
}
