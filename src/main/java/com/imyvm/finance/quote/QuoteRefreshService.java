package com.imyvm.finance.quote;

import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.FinanceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuoteRefreshService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("imyvm_finance/quotes");
        private final QuoteSnapshotStore store;
    private final SidecarClient client;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long refreshMinutes;

    public QuoteRefreshService(QuoteSnapshotStore store) {
        this(store, FinanceConfig.defaults());
    }

    public QuoteRefreshService(QuoteSnapshotStore store, FinanceConfig config) {
        this.store = store;
        this.client = new SidecarClient(
            config.sidecarEndpoint(),
            config.sidecarConnectTimeout(),
            config.sidecarReadTimeout());
        this.refreshMinutes = config.quoteRefreshMinutes();
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
                    return;
                }

                store.save(snapshot);
                LOGGER.info("Stored quote snapshot {} with {} quotes",
                    snapshot.snapshotId(), snapshot.quotes().size());
            } catch (Exception exception) {
                LOGGER.error("Failed to store sidecar quote snapshot", exception);
            } finally {
                refreshing.set(false);
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true))
            executor.shutdownNow();
    }
}
