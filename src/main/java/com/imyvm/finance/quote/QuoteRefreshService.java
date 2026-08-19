package com.imyvm.finance.quote;

import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.FinanceConfig;
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
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class QuoteRefreshService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("imyvm_finance/quotes");
        private final QuoteSnapshotStore store;
    private final DirectMarketQuoteClient client;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long pollIntervalMinutes;
    private final long pollDelaySeconds;
    private final long jitterSeconds;
    private final SplittableRandom random;
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
        this.client = new DirectMarketQuoteClient(
            config.quoteConnectTimeout(),
            config.quoteReadTimeout(),
            config.marketHolidays(), config.marketEnabled(), config.disabledProviders());
        this.pollIntervalMinutes = config.quotePollIntervalMinutes();
        this.pollDelaySeconds = config.quotePollDelaySeconds();
        this.jitterSeconds = config.quoteJitterSeconds();
        this.random = config.quoteRandomSeed() == 0 ? new SplittableRandom() : new SplittableRandom(config.quoteRandomSeed());
        this.alertConsumer = alertConsumer;
        this.snapshotConsumer = snapshotConsumer;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "imyvm-finance-quotes");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        refresh();
        scheduleNext(nextPollDelay());
    }

    private void scheduleNext(long delayMillis) {
        executor.schedule(() -> {
            refresh();
            if (!closed.get())
                scheduleNext(nextPollDelay());
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private long nextPollDelay() {
        long base = millisecondsUntilPollNode(Instant.now(), ZoneId.systemDefault(), pollIntervalMinutes, pollDelaySeconds);
        if (jitterSeconds == 0)
            return base;
        long jitter = random.nextLong(-jitterSeconds, jitterSeconds + 1) * 1000L;
        return Math.max(1000L, base + jitter);
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

    public void setMarketEnabled(String market, boolean enabled) {
        client.setMarketEnabled(market, enabled);
    }

    public void setProviderEnabled(String market, String provider, boolean enabled) {
        client.setProviderEnabled(market, provider, enabled);
    }

    private void refresh() {
        if (closed.get() || !refreshing.compareAndSet(false, true))
            return;

        client.fetch().whenComplete((snapshot, error) -> {
            try {
                if (closed.get())
                    return;
                if (error != null) {
                    LOGGER.warn("Market quote refresh failed: {}", error.getMessage());
                    notifyMarketDataFailure();
                    return;
                }

                store.save(snapshot);
                if (snapshot.alerts().isEmpty() && !snapshot.snapshotId().equals(lastSnapshotId)) {
                    lastSnapshotId = snapshot.snapshotId();
                    snapshotConsumer.accept(snapshot);
                }
                notifyMarketDataRecovery();
                for (String alert : snapshot.alerts())
                    notifyAlert(alert);
                LOGGER.info("Stored quote snapshot {} with {} quotes",
                    snapshot.snapshotId(), snapshot.quotes().size());
            } catch (Exception exception) {
                LOGGER.error("Failed to store market quote snapshot", exception);
            } finally {
                refreshing.set(false);
            }
        });
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true))
            executor.shutdownNow();
    }
}
