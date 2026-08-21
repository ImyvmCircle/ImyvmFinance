package com.imyvm.finance.quote;

import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.FinanceConfig;
import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.market.QuoteOrigin;
import com.imyvm.finance.market.QuoteSnapshot;
import com.imyvm.finance.storage.StoredQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

public final class QuoteRefreshService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("imyvm_finance/quotes");
        private final QuoteSnapshotStore store;
    private final DirectMarketQuoteClient client;
    private final java.util.Map<String, java.util.Set<java.time.LocalDate>> marketHolidays;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<com.imyvm.finance.market.QuoteSnapshot>> inFlight = new AtomicReference<>();
    private final AtomicLong scheduleGeneration = new AtomicLong();
    private final AtomicBoolean skipNextScheduledRefresh = new AtomicBoolean();
    private volatile boolean started;
    private volatile boolean idleMode;
    private volatile long modeChangedAtEpochMillis;
    private volatile String modeReason = "startup";
    private final long pollIntervalMinutes;
    private final long idlePollIntervalMinutes;
    private final long pollDelaySeconds;
    private final long jitterSeconds;
    private final SplittableRandom random;
    private final long randomSeed;
    private final long simulationSeed;
    private final java.util.Map<String, Long> simulationDefaultPrices;
    private final SimulationModelConfig simulationModels;
    private final long pollIntervalMillis;
    private final java.util.Map<String, Long> simulationStartedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long nextNominalPollAtEpochMillis;
    private volatile long lastNominalPollAtEpochMillis;
    private volatile long lastJitterSeconds;
    private volatile long lastScheduledPollAtEpochMillis;
    private volatile long lastRefreshStartedAtEpochMillis;
    private volatile long lastRefreshCompletedAtEpochMillis;
    private volatile String lastRefreshStatus = "idle";
    private volatile String lastRefreshError;
    private final Consumer<String> alertConsumer;
    private final Consumer<com.imyvm.finance.market.QuoteSnapshot> snapshotConsumer;
    private final Set<String> unavailableInstruments = new HashSet<>();
    private boolean marketDataUnavailable;
    private String lastSnapshotId;

    public QuoteRefreshService(QuoteSnapshotStore store) {
        this(store, FinanceConfig.defaults(), ignored -> { });
    }

    public QuoteRefreshService(QuoteSnapshotStore store, FinanceConfig config) {
        this(store, config, ignored -> { });
    }

    public QuoteRefreshService(QuoteSnapshotStore store, FinanceConfig config, Consumer<String> alertConsumer) {
        this(store, config, alertConsumer, ignored -> { });
    }

    public QuoteRefreshService(QuoteSnapshotStore store, FinanceConfig config,
                               Consumer<String> alertConsumer,
                               Consumer<com.imyvm.finance.market.QuoteSnapshot> snapshotConsumer) {
        this.store = store;
        this.marketHolidays = config.marketHolidays();
        this.client = new DirectMarketQuoteClient(
            config.quoteConnectTimeout(),
            config.quoteReadTimeout(),
            config.marketHolidays(), config.marketEnabled(), config.disabledProviders(), config.providerOrder(), config.quoteProviderBackoffMinutes());
        this.pollIntervalMinutes = config.quotePollIntervalMinutes();
        this.pollIntervalMillis = pollIntervalMinutes * 60_000L;
        this.simulationSeed = config.quoteRandomSeed();
        this.simulationDefaultPrices = config.simulationDefaultPrices();
        this.simulationModels = config.simulationModels();
        this.idlePollIntervalMinutes = config.quoteIdlePollIntervalMinutes();
        this.pollDelaySeconds = config.quotePollDelaySeconds();
        this.jitterSeconds = config.quoteJitterSeconds();
        this.randomSeed = config.quoteRandomSeed();
        this.random = randomSeed == 0 ? new SplittableRandom() : new SplittableRandom(randomSeed);
        this.alertConsumer = alertConsumer;
        this.snapshotConsumer = snapshotConsumer;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "imyvm-finance-quotes");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        started = true;
        modeChangedAtEpochMillis = System.currentTimeMillis();
        refreshNow();
        scheduleNext();
    }

    public void startAfterInitialSnapshot() {
        started = true;
        modeChangedAtEpochMillis = System.currentTimeMillis();
        scheduleNext();
    }

    public synchronized void setIdleMode(boolean idle, String reason) {
        if (closed.get() || idleMode == idle)
            return;
        idleMode = idle;
        modeReason = reason == null ? "unspecified" : reason;
        modeChangedAtEpochMillis = System.currentTimeMillis();
        nextNominalPollAtEpochMillis = 0;
        scheduleGeneration.incrementAndGet();
        if (!idle) {
            client.clearProviderBackoff();
            skipNextScheduledRefresh.set(true);
        }
        if (started)
            scheduleNext();
        LOGGER.info("Market quote scheduler mode changed: mode={} reason={}", idle ? "idle" : "active", modeReason);
    }

    public CompletableFuture<com.imyvm.finance.market.QuoteSnapshot> wakeAndRefresh(String reason) {
        setIdleMode(false, reason);
        return refreshNow();
    }

    public CompletableFuture<com.imyvm.finance.market.QuoteSnapshot> prepareForPlayerQuery(String reason) {
        if (idleMode)
            return wakeAndRefresh(reason);
        CompletableFuture<com.imyvm.finance.market.QuoteSnapshot> current = inFlight.get();
        return current == null ? CompletableFuture.completedFuture(null) : current;
    }

    private void scheduleNext() {
        long generation = scheduleGeneration.get();
        long delayMillis = nextPollDelay();
        executor.schedule(() -> {
            if (closed.get() || generation != scheduleGeneration.get())
                return;
            if (!skipNextScheduledRefresh.compareAndSet(true, false))
                refreshNow();
            if (!closed.get() && generation == scheduleGeneration.get())
                scheduleNext();
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private long nextPollDelay() {
        long now = System.currentTimeMillis();
        long intervalMinutes = idleMode ? idlePollIntervalMinutes : pollIntervalMinutes;
        long intervalMillis = intervalMinutes * 60_000L;
        if (nextNominalPollAtEpochMillis == 0) {
            nextNominalPollAtEpochMillis = now
                + millisecondsUntilPollNode(Instant.ofEpochMilli(now), MarketHours.displayZone(),
                    intervalMinutes, pollDelaySeconds);
        } else {
            while (nextNominalPollAtEpochMillis <= now)
                nextNominalPollAtEpochMillis += intervalMillis;
        }

        long nominalPollAt = nextNominalPollAtEpochMillis;
        nextNominalPollAtEpochMillis += intervalMillis;
        lastNominalPollAtEpochMillis = nominalPollAt;
        long jitterSecondsForNode = jitterSeconds == 0
            ? 0
            : random.nextLong(-jitterSeconds, jitterSeconds + 1);
        long scheduledPollAt = nominalPollAt + jitterSecondsForNode * 1000L;
        lastJitterSeconds = jitterSecondsForNode;
        lastScheduledPollAtEpochMillis = scheduledPollAt;
        long delay = Math.max(1000L, scheduledPollAt - now);
        LOGGER.info("Scheduled market quote refresh: mode={} intervalMinutes={} nominalAt={} jitterSeconds={} scheduledAt={} delaySeconds={}",
            idleMode ? "idle" : "active", intervalMinutes, Instant.ofEpochMilli(nominalPollAt),
            jitterSecondsForNode, Instant.ofEpochMilli(scheduledPollAt), delay / 1000L);
        return delay;
    }

    public static long millisecondsUntilPollNode(Instant instant, ZoneId zone, long intervalMinutes, long delaySeconds) {
        ZonedDateTime now = instant.atZone(zone);
        ZonedDateTime nominal = now.withMinute(1).withSecond((int) delaySeconds).withNano(0);
        while (!nominal.isAfter(now))
            nominal = nominal.plusMinutes(intervalMinutes);
        return nominal.toInstant().toEpochMilli() - instant.toEpochMilli();
    }

    public String providerStatus() {
        return client.controlStatus();
    }

    public String schedulerStatus() {
        long intervalMinutes = idleMode ? idlePollIntervalMinutes : pollIntervalMinutes;
        return "{\"mode\":" + jsonString(idleMode ? "idle" : "active")
            + ",\"modeReason\":" + jsonString(modeReason)
            + ",\"modeChangedAt\":" + jsonTime(modeChangedAtEpochMillis)
            + ",\"pollIntervalMinutes\":" + pollIntervalMinutes
            + ",\"idlePollIntervalMinutes\":" + idlePollIntervalMinutes
            + ",\"currentPollIntervalMinutes\":" + intervalMinutes
            + ",\"pollDelaySeconds\":" + pollDelaySeconds
            + ",\"jitterSeconds\":" + jitterSeconds
            + ",\"randomSeed\":" + randomSeed
            + ",\"lastNominalPollAt\":" + jsonTime(lastNominalPollAtEpochMillis)
            + ",\"lastJitterSeconds\":" + lastJitterSeconds
            + ",\"lastScheduledPollAt\":" + jsonTime(lastScheduledPollAtEpochMillis)
            + ",\"nextNominalPollAt\":" + jsonTime(nextNominalPollAtEpochMillis)
            + ",\"lastRefreshStartedAt\":" + jsonTime(lastRefreshStartedAtEpochMillis)
            + ",\"lastRefreshCompletedAt\":" + jsonTime(lastRefreshCompletedAtEpochMillis)
            + ",\"lastRefreshStatus\":" + jsonString(lastRefreshStatus)
            + ",\"lastRefreshError\":" + jsonString(lastRefreshError)
            + ",\"lastSnapshotId\":" + jsonString(lastSnapshotId)
            + ",\"simulationMarkets\":" + jsonSimulationMarkets() + "}";
    }

    public void clearProviderBackoff() {
        client.clearProviderBackoff();
    }

    public boolean isIdleMode() {
        return idleMode;
    }

    public void setMarketEnabled(String market, boolean enabled) {
        client.setMarketEnabled(market, enabled);
    }

    public void setProviderEnabled(String market, String provider, boolean enabled) {
        client.setProviderEnabled(market, provider, enabled);
    }

    private CompletableFuture<com.imyvm.finance.market.QuoteSnapshot> refreshNow() {
        if (closed.get())
            return CompletableFuture.failedFuture(new IllegalStateException("quote service closed"));
        CompletableFuture<com.imyvm.finance.market.QuoteSnapshot> existing = inFlight.get();
        if (existing != null)
            return existing;
        CompletableFuture<com.imyvm.finance.market.QuoteSnapshot> created = new CompletableFuture<>();
        if (!inFlight.compareAndSet(null, created))
            return inFlight.get();
        lastRefreshStartedAtEpochMillis = System.currentTimeMillis();
        lastRefreshStatus = "running";
        lastRefreshError = null;
        client.fetch().whenComplete((snapshot, error) -> {
            try {
                if (closed.get()) {
                    created.completeExceptionally(new IllegalStateException("quote service closed"));
                    return;
                }
                if (error != null) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    lastRefreshStatus = "failed";
                    lastRefreshError = cause.getMessage();
                    LOGGER.warn("Market quote refresh failed: {}", cause.getMessage());
                    notifyMarketDataFailure();
                    try {
                        java.util.Optional<QuoteSnapshot> simulated = SimulationSnapshotBuilder.build(null, store,
                            lastScheduledPollAtEpochMillis == 0 ? System.currentTimeMillis() : lastScheduledPollAtEpochMillis,
                            pollIntervalMillis, simulationSeed, simulationDefaultPrices, simulationModels,
                            simulationStartedAt, marketHolidays);
                        if (simulated.isPresent()) {
                            store.save(simulated.get());
                            lastRefreshStatus = "simulated";
                            if (!simulated.get().snapshotId().equals(lastSnapshotId)) {
                                lastSnapshotId = simulated.get().snapshotId();
                                snapshotConsumer.accept(simulated.get());
                            }
                            created.complete(simulated.get());
                        } else {
                            created.completeExceptionally(cause);
                        }
                    } catch (Exception simulationError) {
                        created.completeExceptionally(simulationError);
                    }
                    return;
                }

                snapshot = SimulationSnapshotBuilder.build(snapshot, store,
                    lastScheduledPollAtEpochMillis == 0 ? System.currentTimeMillis() : lastScheduledPollAtEpochMillis,
                    pollIntervalMillis, simulationSeed, simulationDefaultPrices, simulationModels,
                    simulationStartedAt, marketHolidays).orElse(snapshot);
                store.save(snapshot);
                lastRefreshStatus = "success";
                if (!snapshot.snapshotId().equals(lastSnapshotId)
                    && (snapshot.alerts().isEmpty() || snapshot.quotes().stream().anyMatch(quote -> quote.origin() == com.imyvm.finance.market.QuoteOrigin.SIMULATED))) {
                    lastSnapshotId = snapshot.snapshotId();
                    snapshotConsumer.accept(snapshot);
                }
                notifyMarketDataRecovery();
                for (String alert : snapshot.alerts())
                    notifyAlert(alert);
                LOGGER.info("Stored quote snapshot {} with {} quotes", snapshot.snapshotId(), snapshot.quotes().size());
                created.complete(snapshot);
            } catch (Exception exception) {
                lastRefreshStatus = "failed";
                lastRefreshError = exception.getMessage();
                LOGGER.error("Failed to store market quote snapshot", exception);
                created.completeExceptionally(exception);
            } finally {
                lastRefreshCompletedAtEpochMillis = System.currentTimeMillis();
                inFlight.compareAndSet(created, null);
            }
        });
        return created;
    }

    private synchronized void notifyMarketDataFailure() {
        if (!marketDataUnavailable) {
            marketDataUnavailable = true;
            alertConsumer.accept("failed:all");
        }
    }

    private synchronized void notifyMarketDataRecovery() {
        if (marketDataUnavailable) {
            marketDataUnavailable = false;
            alertConsumer.accept("recovered:all");
        }
    }

    private synchronized void notifyAlert(String alert) {
        if (alert.startsWith("failed:")) {
            String symbol = alert.substring("failed:".length());
            if (unavailableInstruments.add(symbol))
                alertConsumer.accept(alert);
            return;
        }
        if (alert.startsWith("recovered:")) {
            String symbol = alert.substring("recovered:".length());
            if (unavailableInstruments.remove(symbol))
                alertConsumer.accept(alert);
        }
    }


    private String jsonSimulationMarkets() {
        return simulationStartedAt.keySet().stream().sorted().map(QuoteRefreshService::jsonString).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonTime(long epochMillis) {
        return epochMillis == 0 ? "null" : jsonString(Instant.ofEpochMilli(epochMillis).toString());
    }

    private static String jsonString(String value) {
        if (value == null)
            return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            long endedAt = System.currentTimeMillis();
            for (long sessionId : simulationStartedAt.values()) {
                try {
                    store.abortSimulation(sessionId, endedAt);
                } catch (Exception exception) {
                    LOGGER.error("Failed to abort simulation session {}", sessionId, exception);
                }
            }
            simulationStartedAt.clear();
            executor.shutdownNow();
        }
    }
}
