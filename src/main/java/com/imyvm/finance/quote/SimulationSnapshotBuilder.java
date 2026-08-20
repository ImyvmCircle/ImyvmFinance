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
        if (fetched == null)
            failedMarkets.addAll(Set.of("CN", "CRYPTO"));
        if (failedMarkets.isEmpty())
            return Optional.of(withNodeTime(fetched, nodeTime));

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
            int iteration = (int) store.simulationNodeCount(sessionId) + 1;
            for (Instrument instrument : Instrument.values()) {
                if (!instrument.market().equals(market))
                    continue;
                Optional<StoredQuote> previous = store.findLatest(instrument);
                if (previous.isEmpty())
                    continue;
                if (previous.get().quote().status() != MarketStatus.OPEN) {
                    MarketQuote old = previous.get().quote();
                    quotes.put(instrument, old);
                    store.recordSimulationNode(sessionId, nodeTime, instrument.symbol(), previous.get().source(), old.priceScaled(), 0, old.priceScaled());
                    continue;
                }
                List<StoredQuote> history = store.findRecentRealQuotes(instrument, 120);
                MarketQuote next;
                history = SimulatedQuoteGenerator.selectEligible(history, intervalMillis);
                if (history.size() == 5) {
                    next = SimulatedQuoteGenerator.next(instrument, history, previous.get().quote(), seed, sessionId, iteration, sessionFunction.getValue());
                    store.recordSimulationNode(sessionId, nodeTime, instrument.symbol(), history.getLast().source(), previous.get().quote().priceScaled(), next.changeBps(), next.priceScaled());
                } else {
                    MarketQuote old = previous.get().quote();
                    next = new MarketQuote(instrument, old.name(), old.priceScaled(), 0,
                        MarketStatus.UNAVAILABLE, QuoteOrigin.SIMULATED);
                    store.recordSimulationNode(sessionId, nodeTime, instrument.symbol(), previous.get().source(), old.priceScaled(), 0, old.priceScaled());
                }
                quotes.put(instrument, next);
            }
        }
        if (quotes.isEmpty())
            return Optional.empty();
        List<String> alerts = fetched == null ? List.of() : fetched.alerts();
        return Optional.of(new QuoteSnapshot(
            "simulated-" + nodeTime,
            fetched == null ? "simulated" : fetched.source(),
            now, nodeTime, new ArrayList<>(quotes.values()), alerts, nodeTime));
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
