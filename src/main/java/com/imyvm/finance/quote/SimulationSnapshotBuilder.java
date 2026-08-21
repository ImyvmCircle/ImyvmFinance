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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SimulationSnapshotBuilder {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("imyvm_finance/simulation");
    private SimulationSnapshotBuilder() { }

    public static Optional<QuoteSnapshot> build(QuoteSnapshot fetched, QuoteSnapshotStore store, long nodeTime,
                                                long intervalMillis, long seed, Map<String, Long> defaultPrices,
                                                Map<String, Long> sessionStarts, Map<String, java.util.Set<java.time.LocalDate>> marketHolidays) throws Exception {
        Set<String> failedMarkets = failedMarkets(fetched);
        if (fetched == null) failedMarkets.addAll(Set.of("CN", "CRYPTO"));
        long effectiveNodeTime = fetched == null ? nodeTime : fetched.nodeTimeEpochMillis();
        if (fetched != null) {
            long recoveredAt = System.currentTimeMillis();
            for (String market : new HashSet<>(sessionStarts.keySet())) {
                if (!failedMarkets.contains(market)) {
                    Long sessionId = sessionStarts.remove(market);
                    if (sessionId != null)
                        store.finishSimulation(sessionId, recoveredAt);
                }
            }
        }
        if (failedMarkets.isEmpty()) return Optional.of(withNodeTime(fetched, effectiveNodeTime));

        Map<Instrument, MarketQuote> quotes = new HashMap<>();
        Map<String, String> activeFunction = store.activeSimulationLayerFormulas();
        if (fetched != null)
            for (MarketQuote quote : fetched.quotes())
                if (!failedMarkets.contains(quote.instrument().market())) quotes.put(quote.instrument(), quote);

        long startedAt = System.currentTimeMillis();
        for (String market : failedMarkets) {
            Long existingSession = sessionStarts.get(market);
            long sessionId = existingSession == null ? simulationSessionId(startedAt, market) : existingSession;
            Map<String, String> sessionFunction = activeFunction;
            if (existingSession == null) {
                sessionStarts.put(market, sessionId);
                store.beginSimulation(sessionId, market, startedAt, "three_layer", "LONG + MEDIUM + SHORT", seed, intervalMillis, SimulatedQuoteGenerator.intervalToleranceMillis(intervalMillis), 0);
                store.freezeSimulationLayers(sessionId);
            }
            for (Instrument instrument : Instrument.values()) {
                if (!instrument.market().equals(market)) continue;
                Optional<StoredQuote> storedPrevious = store.findLatest(instrument);
                MarketQuote previous = storedPrevious.map(StoredQuote::quote).orElseGet(() -> defaultQuote(instrument, defaultPrices, nodeTime, marketHolidays));
                if (previous == null) continue;
                previous = new MarketQuote(instrument, previous.name(), previous.priceScaled(), previous.changeBps(),
                    MarketHours.status(instrument.market(), java.time.Instant.ofEpochMilli(nodeTime), marketHolidays.getOrDefault(instrument.market(), java.util.Set.of())), previous.origin());
                String source = storedPrevious.map(StoredQuote::source).orElse("config-default");
                int configuredFactor = store.simulationFactor(instrument.symbol());
                int factor = store.simulationFactorForSession(sessionId, instrument.symbol(), configuredFactor);
                var state = store.findSimulationState(sessionId, instrument.symbol()).orElse(null);
                int iteration = state == null ? 1 : state.iteration() + 1;
                double trendState = state == null ? 0.0 : state.trendState();
                MarketQuote next;
                SimulatedQuoteGenerator.Step step = null;
                double nextTrendState;
                long randomBps;
                if (previous.status() != MarketStatus.OPEN) {
                    next = new MarketQuote(instrument, previous.name(), previous.priceScaled(), 0, previous.status(), QuoteOrigin.SIMULATED);
                    nextTrendState = trendState; randomBps = 0;
                } else {
                    step = SimulatedQuoteGenerator.nextStep(instrument, previous, seed, iteration, factor, trendState, sessionFunction);
                    next = step.quote(); nextTrendState = step.trendState(); randomBps = step.randomBps();
                }
                quotes.put(instrument, next);
                store.recordSimulationNode(sessionId, effectiveNodeTime, instrument.symbol(), source, previous.priceScaled(), next.changeBps(), next.priceScaled(), factor);
                String layerParameters = "factor=" + factor + ",trend=" + (step == null ? 0 : step.trendBps()) + ",random=" + (step == null ? 0 : step.randomBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime, instrument.symbol(), "LONG", layerParameters, step == null ? 0 : step.longBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime, instrument.symbol(), "MEDIUM", layerParameters, step == null ? 0 : step.mediumBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime, instrument.symbol(), "SHORT", layerParameters, step == null ? 0 : step.shortBps());
                LOGGER.info("Simulation node: session={} market={} symbol={} parameters={} longBps={} mediumBps={} shortBps={} totalBps={} appliedBps={} previousPrice={} newPrice={}", sessionId, market, instrument.symbol(), layerParameters, step == null ? 0 : step.longBps(), step == null ? 0 : step.mediumBps(), step == null ? 0 : step.shortBps(), step == null ? 0 : step.unclampedBps(), next.changeBps(), previous.priceScaled(), next.priceScaled());
                store.recordSimulationNodeInput(sessionId, effectiveNodeTime, instrument.symbol(), 0, source, storedPrevious.map(StoredQuote::nodeTimeEpochMillis).orElse(effectiveNodeTime), previous.priceScaled());
                store.saveSimulationState(sessionId, instrument.symbol(), nextTrendState, iteration);
            }
        }
        if (quotes.isEmpty()) return Optional.empty();
        return Optional.of(new QuoteSnapshot("simulated-" + effectiveNodeTime, fetched == null ? "simulated" : fetched.source(),
            System.currentTimeMillis(), effectiveNodeTime, new ArrayList<>(quotes.values()), fetched == null ? java.util.List.of() : fetched.alerts(), effectiveNodeTime));
    }

    private static MarketQuote defaultQuote(Instrument instrument, Map<String, Long> defaultPrices, long nodeTime, Map<String, java.util.Set<java.time.LocalDate>> marketHolidays) {
        Long price = defaultPrices.get(instrument.symbol());
        MarketStatus status = MarketHours.status(instrument.market(), java.time.Instant.ofEpochMilli(nodeTime), marketHolidays.getOrDefault(instrument.market(), java.util.Set.of()));
        return price == null || price <= 0 ? null : new MarketQuote(instrument, instrument.symbol(), price, 0, status, QuoteOrigin.SIMULATED);
    }

    private static long simulationSessionId(long startedAt, String market) { return startedAt * 10L + ("CRYPTO".equals(market) ? 2L : 1L); }

    private static Set<String> failedMarkets(QuoteSnapshot snapshot) {
        Set<String> result = new HashSet<>();
        if (snapshot != null) for (String alert : snapshot.alerts()) if (alert.startsWith("failed:market:")) result.add(alert.substring("failed:market:".length()));
        return result;
    }

    private static QuoteSnapshot withNodeTime(QuoteSnapshot snapshot, long nodeTime) {
        if (snapshot.nodeTimeEpochMillis() == nodeTime) return snapshot;
        return new QuoteSnapshot(snapshot.snapshotId(), snapshot.source(), snapshot.fetchedAtEpochMillis(), snapshot.marketTimeEpochMillis(), snapshot.quotes(), snapshot.alerts(), nodeTime);
    }
}
