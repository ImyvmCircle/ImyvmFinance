package com.imyvm.finance.quote;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.market.QuoteOrigin;
import com.imyvm.finance.market.QuoteSnapshot;
import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.storage.StoredQuote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SimulationSnapshotBuilder {
    private SimulationSnapshotBuilder() {
    }

    public static Optional<QuoteSnapshot> build(QuoteSnapshot fetched, QuoteSnapshotStore store,
                                                long nodeTime, long intervalMillis, long seed,
                                                Map<String, Long> sessionStarts) throws Exception {
        Set<String> failedMarkets = failedMarkets(fetched);
        long effectiveNodeTime = fetched == null ? System.currentTimeMillis() : fetched.nodeTimeEpochMillis();
        if (fetched == null)
            failedMarkets.addAll(Set.of("CN", "CRYPTO"));
        if (failedMarkets.isEmpty())
            return Optional.of(withNodeTime(fetched, effectiveNodeTime));

        Map<Instrument, MarketQuote> quotes = new HashMap<>();
        Map.Entry<String, String> activeFunction = store.activeSimulationFunction();
        if (fetched != null)
            for (MarketQuote quote : fetched.quotes())
                if (!failedMarkets.contains(quote.instrument().market()))
                    quotes.put(quote.instrument(), quote);

        long now = System.currentTimeMillis();
        for (String market : failedMarkets) {
            Long existingSession = sessionStarts.get(market);
            long sessionId = existingSession == null ? simulationSessionId(now, market) : existingSession;
            Map.Entry<String, String> sessionFunction = existingSession == null ? activeFunction : store.simulationFunctionForSession(sessionId);
            if (existingSession == null) {
                sessionStarts.put(market, sessionId);
                store.beginSimulation(sessionId, market, now, sessionFunction.getKey(), sessionFunction.getValue(), seed);
            }
            for (Instrument instrument : Instrument.values()) {
                if (!instrument.market().equals(market))
                    continue;
                Optional<StoredQuote> previous = store.findLatest(instrument);
                if (previous.isEmpty())
                    continue;
                int iteration = (int) store.simulationNodeCount(sessionId, instrument.symbol()) + 1;
                if (previous.get().quote().status() != MarketStatus.OPEN) {
                    MarketQuote old = previous.get().quote();
                    MarketQuote simulated = new MarketQuote(instrument, old.name(), old.priceScaled(), 0, old.status(), QuoteOrigin.SIMULATED);
                    quotes.put(instrument, simulated);
                    store.recordSimulationNode(sessionId, effectiveNodeTime, instrument.symbol(), previous.get().source(), old.priceScaled(), 0, old.priceScaled());
                    store.recordSimulationNodeInput(sessionId, effectiveNodeTime, instrument.symbol(), 0, previous.get().source(), previous.get().nodeTimeEpochMillis(), old.priceScaled());
                    continue;
                }
                List<StoredQuote> history = store.findRecentRealQuotes(instrument, 120);
                MarketQuote next;
                history = SimulatedQuoteGenerator.selectEligible(history, intervalMillis);
                if (history.size() == 5) {
                    next = SimulatedQuoteGenerator.next(instrument, history, previous.get().quote(), seed, sessionId, iteration, intervalMillis, sessionFunction.getValue());
                    String inputSources = history.stream().map(StoredQuote::source).distinct().collect(java.util.stream.Collectors.joining(","));
                    store.recordSimulationNode(sessionId, effectiveNodeTime, instrument.symbol(), inputSources, previous.get().quote().priceScaled(), next.changeBps(), next.priceScaled());
                    for (int inputIndex = 0; inputIndex < history.size(); inputIndex++) {
                        StoredQuote input = history.get(inputIndex);
                        store.recordSimulationNodeInput(sessionId, effectiveNodeTime, instrument.symbol(), inputIndex, input.source(), input.nodeTimeEpochMillis(), input.quote().priceScaled());
                    }
                } else {
                    MarketQuote old = previous.get().quote();
                    next = new MarketQuote(instrument, old.name(), old.priceScaled(), 0,
                        MarketStatus.UNAVAILABLE, QuoteOrigin.SIMULATED);
                    store.recordSimulationNode(sessionId, effectiveNodeTime, instrument.symbol(), previous.get().source(), old.priceScaled(), 0, old.priceScaled());
                    store.recordSimulationNodeInput(sessionId, effectiveNodeTime, instrument.symbol(), 0, previous.get().source(), previous.get().nodeTimeEpochMillis(), old.priceScaled());
                }
                quotes.put(instrument, next);
            }
        }
        if (quotes.isEmpty())
            return Optional.empty();
        List<String> alerts = fetched == null ? List.of() : fetched.alerts();
        return Optional.of(new QuoteSnapshot(
            "simulated-" + effectiveNodeTime,
            fetched == null ? "simulated" : fetched.source(),
            now, effectiveNodeTime, new ArrayList<>(quotes.values()), alerts, effectiveNodeTime));
    }

    private static long simulationSessionId(long startedAt, String market) {
        return startedAt * 10L + ("CRYPTO".equals(market) ? 2L : 1L);
    }

    private static Set<String> failedMarkets(QuoteSnapshot snapshot) {
        Set<String> result = new HashSet<>();
        if (snapshot == null)
            return result;
        for (String alert : snapshot.alerts())
            if (alert.startsWith("failed:market:"))
                result.add(alert.substring("failed:market:".length()));
        return result;
    }


    private static QuoteSnapshot withNodeTime(QuoteSnapshot snapshot, long nodeTime) {
        if (snapshot.nodeTimeEpochMillis() == nodeTime)
            return snapshot;
        return new QuoteSnapshot(snapshot.snapshotId(), snapshot.source(), snapshot.fetchedAtEpochMillis(),
            snapshot.marketTimeEpochMillis(), snapshot.quotes(), snapshot.alerts(), nodeTime);
    }
}
