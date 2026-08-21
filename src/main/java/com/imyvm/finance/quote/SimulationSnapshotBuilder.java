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

    private SimulationSnapshotBuilder() {
    }

    public static Optional<QuoteSnapshot> build(
        QuoteSnapshot fetched,
        QuoteSnapshotStore store,
        long nodeTime,
        long intervalMillis,
        long seed,
        Map<String, Long> defaultPrices,
        SimulationModelConfig configuredModels,
        Map<String, Long> sessionStarts,
        Map<String, java.util.Set<java.time.LocalDate>> marketHolidays
    ) throws Exception {
        Set<String> failedMarkets = failedMarkets(fetched);
        if (fetched == null)
            failedMarkets.addAll(Instrument.markets());
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
        if (failedMarkets.isEmpty())
            return Optional.of(withNodeTime(fetched, effectiveNodeTime));

        Map<Instrument, MarketQuote> quotes = new HashMap<>();
        if (fetched != null)
            for (MarketQuote quote : fetched.quotes())
                if (!failedMarkets.contains(quote.instrument().market()))
                    quotes.put(quote.instrument(), quote);

        long startedAt = System.currentTimeMillis();
        for (String market : failedMarkets) {
            Long existingSession = sessionStarts.get(market);
            long sessionId = existingSession == null
                ? simulationSessionId(startedAt, market) : existingSession;
            String modelId;
            SimulationModelConfig sessionModels;
            if (existingSession == null) {
                modelId = store.activeSimulationModel(configuredModels.defaultModelId());
                configuredModels.model(modelId);
                String frozenConfiguration = configuredModels.snapshot(modelId);
                sessionStarts.put(market, sessionId);
                store.beginSimulation(sessionId, market, startedAt, modelId,
                    frozenConfiguration, seed, intervalMillis,
                    SimulatedQuoteGenerator.intervalToleranceMillis(intervalMillis), 0);
                store.freezeSimulationLayers(sessionId, modelId,
                    SimulationModelConfig.LAYER_DESCRIPTIONS);
                sessionModels = SimulationModelConfig.fromSnapshot(frozenConfiguration);
            } else {
                var session = store.findSimulationSession(sessionId)
                    .orElseThrow(() -> new IllegalStateException(
                        "missing simulation session: " + sessionId));
                modelId = session.functionId();
                sessionModels = SimulationModelConfig.fromSnapshot(session.formula());
                sessionModels.model(modelId);
            }
            if (store.simulationLayers(sessionId).size() != 3)
                throw new IllegalStateException(
                    "simulation session layers are incomplete: " + sessionId);

            for (Instrument instrument : Instrument.values()) {
                if (!instrument.market().equals(market))
                    continue;
                Optional<StoredQuote> storedPrevious = store.findLatest(instrument);
                MarketQuote previous = storedPrevious.map(StoredQuote::quote)
                    .orElseGet(() -> defaultQuote(instrument, defaultPrices, nodeTime,
                        marketHolidays));
                if (previous == null)
                    continue;
                previous = new MarketQuote(instrument, previous.name(),
                    previous.priceScaled(), previous.changeBps(),
                    MarketHours.status(instrument.sourceMarket(),
                        java.time.Instant.ofEpochMilli(nodeTime),
                        marketHolidays.getOrDefault(instrument.sourceMarket(),
                            java.util.Set.of())),
                    previous.origin());
                String source = storedPrevious.map(StoredQuote::source)
                    .orElse("config-default");
                String inputSource = source + " [" + previous.origin().name() + "]";
                int configuredFactor = store.simulationFactor(instrument.symbol());
                int factor = store.simulationFactorForSession(sessionId,
                    instrument.symbol(), configuredFactor);
                var storedState = store.findSimulationState(sessionId,
                    instrument.symbol()).orElse(null);
                int iteration = storedState == null ? 0 : storedState.iteration();
                SimulatedQuoteGenerator.State modelState = storedState == null
                    ? SimulatedQuoteGenerator.State.initial()
                    : SimulatedQuoteGenerator.State.parse(storedState.modelState());
                MarketQuote next;
                SimulatedQuoteGenerator.Step step = null;
                if (previous.status() != MarketStatus.OPEN) {
                    next = new MarketQuote(instrument, previous.name(),
                        previous.priceScaled(), 0, previous.status(),
                        QuoteOrigin.SIMULATED);
                } else {
                    iteration++;
                    step = SimulatedQuoteGenerator.nextStep(instrument, previous,
                        seed, iteration, factor, modelState, sessionModels, modelId,
                        intervalMillis);
                    next = step.quote();
                    modelState = step.state();
                }
                quotes.put(instrument, next);
                store.recordSimulationNode(sessionId, effectiveNodeTime,
                    instrument.symbol(), inputSource, previous.priceScaled(),
                    next.changeBps(), next.priceScaled(), factor);
                String parameters = step == null
                    ? "factor=" + factor + ",market=closed,state=frozen"
                    : "factor=" + factor
                        + ",correlationScale=" + format(step.correlationScale())
                        + ",variance=" + format(step.varianceMultiplier())
                        + ",switches=" + String.join(",", step.switches());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime,
                    instrument.symbol(), "LONG", parameters,
                    step == null ? 0.0 : step.longBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime,
                    instrument.symbol(), "MEDIUM", parameters,
                    step == null ? 0.0 : step.mediumBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime,
                    instrument.symbol(), "SHORT", parameters,
                    step == null ? 0.0 : step.shortBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime,
                    instrument.symbol(), "STOCHASTIC", parameters,
                    step == null ? 0.0 : step.stochasticBps());
                store.recordSimulationNodeLayer(sessionId, effectiveNodeTime,
                    instrument.symbol(), "JUMP", parameters,
                    step == null ? 0.0 : step.jumpBps());
                LOGGER.info(
                    "Simulation node: session={} model={} market={} symbol={} parameters={} longBps={} mediumBps={} shortBps={} stochasticBps={} jumpBps={} totalBps={} appliedBps={} previousPrice={} newPrice={}",
                    sessionId, modelId, market, instrument.symbol(), parameters,
                    step == null ? 0.0 : step.longBps(),
                    step == null ? 0.0 : step.mediumBps(),
                    step == null ? 0.0 : step.shortBps(),
                    step == null ? 0.0 : step.stochasticBps(),
                    step == null ? 0.0 : step.jumpBps(),
                    step == null ? 0.0 : step.unclampedBps(),
                    step == null ? 0.0 : step.appliedBps(),
                    previous.priceScaled(), next.priceScaled());
                store.recordSimulationNodeInput(sessionId, effectiveNodeTime,
                    instrument.symbol(), 0, inputSource,
                    storedPrevious.map(StoredQuote::nodeTimeEpochMillis)
                        .orElse(effectiveNodeTime),
                    previous.priceScaled());
                store.saveSimulationState(sessionId, instrument.symbol(),
                    modelState.varianceMultiplier(), iteration,
                    modelState.serialize());
            }
        }
        if (quotes.isEmpty())
            return Optional.empty();
        return Optional.of(new QuoteSnapshot(
            "simulated-" + effectiveNodeTime,
            fetched == null ? "simulated"
                : quotes.values().stream().anyMatch(
                    quote -> quote.origin() == QuoteOrigin.SIMULATED)
                    ? "mixed" : fetched.source(),
            System.currentTimeMillis(), effectiveNodeTime,
            new ArrayList<>(quotes.values()),
            fetched == null ? java.util.List.of() : fetched.alerts(),
            effectiveNodeTime));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static MarketQuote defaultQuote(
        Instrument instrument,
        Map<String, Long> defaultPrices,
        long nodeTime,
        Map<String, java.util.Set<java.time.LocalDate>> marketHolidays
    ) {
        Long price = defaultPrices.get(instrument.symbol());
        MarketStatus status = MarketHours.status(instrument.sourceMarket(),
            java.time.Instant.ofEpochMilli(nodeTime),
            marketHolidays.getOrDefault(instrument.sourceMarket(),
                java.util.Set.of()));
        return price == null || price <= 0 ? null
            : new MarketQuote(instrument, instrument.symbol(), price, 0, status,
                QuoteOrigin.SIMULATED);
    }

    private static long simulationSessionId(long startedAt, String market) {
        return startedAt * 10L + switch (market) {
            case "CN" -> 1L;
            case "CRYPTO" -> 2L;
            case "GOLD" -> 3L;
            case "BOND" -> 4L;
            case "FUTURES" -> 5L;
            default -> 9L;
        };
    }

    private static Set<String> failedMarkets(QuoteSnapshot snapshot) {
        Set<String> result = new HashSet<>();
        if (snapshot != null)
            for (String alert : snapshot.alerts())
                if (alert.startsWith("failed:market:"))
                    result.add(alert.substring("failed:market:".length()));
        return result;
    }

    private static QuoteSnapshot withNodeTime(QuoteSnapshot snapshot, long nodeTime) {
        if (snapshot.nodeTimeEpochMillis() == nodeTime)
            return snapshot;
        return new QuoteSnapshot(snapshot.snapshotId(), snapshot.source(),
            snapshot.fetchedAtEpochMillis(), snapshot.marketTimeEpochMillis(),
            snapshot.quotes(), snapshot.alerts(), nodeTime);
    }
}
