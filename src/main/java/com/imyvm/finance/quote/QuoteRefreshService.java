package com.imyvm.finance.quote;

import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.FinanceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class QuoteRefreshService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("imyvm_finance/quotes");
        private final QuoteSnapshotStore store;
    private final SidecarClient client;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long refreshMinutes;
    private final Consumer<String> alertConsumer;
    private final Consumer<com.imyvm.finance.market.QuoteSnapshot> snapshotConsumer;
    private final Set<String> unavailableInstruments = new HashSet<>();
    private boolean sidecarUnavailable;

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
        this.client = new SidecarClient(
            config.sidecarEndpoint(),
            config.sidecarConnectTimeout(),
            config.sidecarReadTimeout());
        this.refreshMinutes = config.quoteRefreshMinutes();
        this.alertConsumer = alertConsumer;
        this.snapshotConsumer = snapshotConsumer;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "imyvm-finance-quotes");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::refresh, 0, refreshMinutes, TimeUnit.MINUTES);
    }

    private void refresh() {
        if (closed.get() || !refreshing.compareAndSet(false, true))
            return;

        client.fetch().whenComplete((snapshot, error) -> {
            try {
                if (closed.get())
                    return;
                if (error != null) {
                    LOGGER.warn("Sidecar quote refresh failed: {}", error.getMessage());
                    notifySidecarFailure();
                    return;
                }

                store.save(snapshot);
                snapshotConsumer.accept(snapshot);
                notifySidecarRecovery();
                for (String alert : snapshot.alerts())
                    notifyAlert(alert);
                LOGGER.info("Stored quote snapshot {} with {} quotes",
                    snapshot.snapshotId(), snapshot.quotes().size());
            } catch (Exception exception) {
                LOGGER.error("Failed to store sidecar quote snapshot", exception);
            } finally {
                refreshing.set(false);
            }
        });
    }

    private synchronized void notifySidecarFailure() {
        if (!sidecarUnavailable) {
            sidecarUnavailable = true;
            alertConsumer.accept("failed:all");
        }
    }

    private synchronized void notifySidecarRecovery() {
        if (sidecarUnavailable) {
            sidecarUnavailable = false;
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
