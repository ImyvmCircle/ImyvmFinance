package com.imyvm.finance.storage;

public record SimulationSessionView(long sessionId, String market, long startedAt, Long endedAt, String functionId, String formula, long seed, String status) {
}
