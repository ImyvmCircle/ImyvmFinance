package com.imyvm.finance.storage;

public record SimulationSessionView(long sessionId, String market, long startedAt, Long endedAt, String functionId, long seed, String status) {
}
