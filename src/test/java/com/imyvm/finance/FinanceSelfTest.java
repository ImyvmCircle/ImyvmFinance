package com.imyvm.finance;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.quote.SidecarClient;
import com.imyvm.finance.storage.StockTradingStore;
import com.imyvm.finance.storage.StockTransactionStore;
import com.imyvm.finance.storage.StoredOrder;
import com.imyvm.finance.trading.StockOrderState;
import com.imyvm.finance.trading.TradeEstimate;
import com.imyvm.finance.trading.TradeSide;
import com.imyvm.finance.transaction.StockOperation;
import com.imyvm.finance.transaction.StockTransaction;
import com.imyvm.finance.transaction.StockTransactionState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public final class FinanceSelfTest {
    private FinanceSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        configChecks();
        sidecarChecks();
        storageChecks();
        System.out.println("FINANCE_SELF_TEST_OK");
    }

    private static void configChecks() throws Exception {
        Path directory = Files.createTempDirectory("imyvm-finance-config-");
        try {
            Path config = directory.resolve("imyvm_finance.properties");
            FinanceConfig defaults = FinanceConfig.load(config);
            check(Files.exists(config), "default config was not created");
            checkEquals("http://127.0.0.1:8765/quotes",
                defaults.sidecarEndpoint().toString(), "default endpoint");
            checkEquals(5L, defaults.quoteRefreshMinutes(), "default refresh");

            Properties properties = new Properties();
            properties.setProperty("sidecar.endpoint", "http://127.0.0.1:9000/quotes");
            properties.setProperty("sidecar.connect-timeout-ms", "1500");
            properties.setProperty("sidecar.read-timeout-ms", "3500");
            properties.setProperty("sidecar.refresh-minutes", "7");
            properties.setProperty("trading.fee-bps", "25");
            properties.setProperty("trading.min-units", "2");
            try (var writer = Files.newBufferedWriter(config)) {
                properties.store(writer, "test");
            }

            FinanceConfig overridden = FinanceConfig.load(config);
            checkEquals("http://127.0.0.1:9000/quotes",
                overridden.sidecarEndpoint().toString(), "custom endpoint");
            checkEquals(1500L, overridden.sidecarConnectTimeout().toMillis(), "connect timeout");
            checkEquals(3500L, overridden.sidecarReadTimeout().toMillis(), "read timeout");
            checkEquals(7L, overridden.quoteRefreshMinutes(), "custom refresh");
            checkEquals(25, overridden.tradingRules().feeBps(), "custom fee");
            checkEquals(2L, overridden.tradingRules().minUnits(), "custom minimum units");
        } finally {
            deleteTree(directory);
        }
    }

    private static void sidecarChecks() {
        String body = """
            {
              "snapshotId": "snapshot-test",
              "source": "test",
              "fetchedAt": "1",
              "marketTime": "2",
              "quotes": [
                {
                  "symbol": "CN:000001",
                  "name": "SSE",
                  "price": "3000.1234",
                  "changePercent": "1.25",
                  "marketStatus": "OPEN"
                },
                {
                  "symbol": "UNKNOWN",
                  "name": "unknown",
                  "price": "1",
                  "changePercent": "0",
                  "marketStatus": "OPEN"
                }
              ]
            }
            """;
        var snapshot = SidecarClient.parse(body);
        checkEquals(1, snapshot.quotes().size(), "whitelisted quote count");
        checkEquals(Instrument.CN_000001, snapshot.quotes().getFirst().instrument(), "quote instrument");
        checkEquals(30_001_234L, snapshot.quotes().getFirst().priceScaled(), "quote price scale");
        checkEquals(125L, snapshot.quotes().getFirst().changeBps(), "quote change scale");
        checkEquals(MarketStatus.OPEN, snapshot.quotes().getFirst().status(), "quote status");
    }

    private static void storageChecks() throws Exception {
        Path directory = Files.createTempDirectory("imyvm-finance-storage-");
        StockTradingStore trading = null;
        StockTransactionStore transactions = null;
        try {
            Path database = directory.resolve("finance.db");
            trading = StockTradingStore.open(database);
            transactions = StockTransactionStore.open(database);

            UUID player = UUID.randomUUID();
            UUID positionId = createActiveBuy(trading, transactions, player, 100L);
            checkEquals(1, trading.findPositions(player).size(), "player position count");
            check(trading.findPositions(UUID.randomUUID()).isEmpty(), "player isolation");

            UUID sellOrderId = UUID.randomUUID();
            UUID sellTransactionId = UUID.randomUUID();
            StockTransaction sell = transaction(
                sellTransactionId, player, StockOperation.SELL, sellOrderId, 3_952L, 4L);
            TradeEstimate sellEstimate =
                estimate(TradeSide.SELL, 40L, "sell-snapshot", 9_900L, 3_960L, 8L, 3_952L);
            transactions.createPrepared(sell);
            trading.createPendingSell(
                sellOrderId, UUID.randomUUID(), positionId, sell, sellEstimate, 4L);
            checkEquals(40L, trading.findPosition(positionId).orElseThrow().frozenUnits(),
                "frozen sell units");
            trading.activateSell(sellTransactionId, positionId, 40L);
            transactions.transition(
                sellTransactionId, StockTransactionState.ECONOMY_CONFIRMED, "credit_accepted", 5L);
            transactions.transition(
                sellTransactionId, StockTransactionState.FINANCE_CONFIRMED, "finance_confirmed", 6L);
            var active = trading.findPosition(positionId).orElseThrow();
            checkEquals(60L, active.remainingUnits(), "remaining units");
            checkEquals(0L, active.frozenUnits(), "released frozen units");
            checkEquals(StockOrderState.ACTIVE, active.state(), "active position state");

            UUID manualOrderId = UUID.randomUUID();
            UUID manualTransactionId = UUID.randomUUID();
            StockTransaction manualSell = transaction(
                manualTransactionId, player, StockOperation.SELL, manualOrderId, 3_952L, 5L);
            TradeEstimate manualEstimate =
                estimate(TradeSide.SELL, 20L, "manual-snapshot", 9_900L, 1_980L, 4L, 1_976L);
            transactions.createPrepared(manualSell);
            trading.createPendingSell(
                manualOrderId, UUID.randomUUID(), positionId, manualSell, manualEstimate, 5L);
            var pending = transactions.markPending(
                manualTransactionId, "economy_credit", "IOException", 7L, 6L);
            checkEquals("economy_credit", pending.failureStage(), "pending failure stage");
            checkEquals("IOException", pending.failureReason(), "pending failure reason");
            checkEquals(1, pending.retryCount(), "pending retry count");
            checkEquals(7L, pending.nextRetryAtEpochMillis(), "pending retry time");
            checkEquals(1, transactions.pendingSettlementCount(player), "pending player count");
            check(transactions.findInterruptedTransactions().isEmpty(), "pending transactions are not interrupted");
            trading.markSellPendingManual(manualTransactionId, positionId);
            StoredOrder order = trading.findOrder(manualTransactionId).orElseThrow();
            transactions.transition(
                manualTransactionId, StockTransactionState.CANCELLED, "manual_released", 7L);
            trading.releaseSell(manualTransactionId, order.positionId(), order.units());
            var restored = trading.findPosition(positionId).orElseThrow();
            checkEquals(60L, restored.remainingUnits(), "restored remaining units");
            checkEquals(0L, restored.frozenUnits(), "restored frozen units");
            checkEquals(StockOrderState.ACTIVE, restored.state(), "restored position state");
            checkEquals(StockOrderState.CANCELLED,
                trading.findOrder(manualTransactionId).orElseThrow().state(),
                "released order state");
        } finally {
            if (trading != null)
                trading.close();
            if (transactions != null)
                transactions.close();
            deleteTree(directory);
        }
    }

    private static UUID createActiveBuy(
        StockTradingStore trading,
        StockTransactionStore transactions,
        UUID player,
        long units) throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        StockTransaction buy =
            transaction(transactionId, player, StockOperation.BUY, orderId, 10_020L, 1L);
        TradeEstimate estimate =
            estimate(TradeSide.BUY, units, "buy-snapshot", 10_000L, 10_000L, 20L, 10_020L);
        transactions.createPrepared(buy);
        trading.createPendingBuy(
            orderId, positionId, UUID.randomUUID(), buy, estimate, 1L, 0L);
        transactions.transition(
            transactionId, StockTransactionState.ECONOMY_CONFIRMED, "debit_accepted", 2L);
        trading.activateBuy(transactionId);
        transactions.transition(
            transactionId, StockTransactionState.FINANCE_CONFIRMED, "finance_confirmed", 3L);
        return positionId;
    }

    private static StockTransaction transaction(
        UUID transactionId,
        UUID player,
        StockOperation operation,
        UUID orderId,
        long amount,
        long createdAt) {
        return new StockTransaction(
            transactionId,
            player,
            operation,
            orderId.toString(),
            Instrument.CN_000001,
            amount,
            StockTransactionState.PREPARED,
            null,
            createdAt,
            createdAt);
    }

    private static TradeEstimate estimate(
        TradeSide side,
        long units,
        String snapshot,
        long executionPrice,
        long gross,
        long fee,
        long settlement) {
        return new TradeEstimate(
            side,
            Instrument.CN_000001,
            units,
            snapshot,
            executionPrice,
            gross,
            fee,
            settlement,
            10,
            20);
    }

    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root))
            return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }
}
