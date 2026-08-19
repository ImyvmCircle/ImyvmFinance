package com.imyvm.finance;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketCommands;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.storage.StockTradingStore;
import com.imyvm.finance.storage.StockTransactionStore;
import com.imyvm.finance.storage.StoredOrder;
import com.imyvm.finance.trading.StockOrderState;
import com.imyvm.finance.trading.TradeEstimate;
import com.imyvm.finance.trading.TradeSide;
import com.imyvm.finance.trading.TradeValidator;
import com.imyvm.finance.trading.TradeValidationException;
import com.imyvm.finance.trading.StockPositionView;
import com.imyvm.finance.trading.TradingRules;
import com.imyvm.finance.trading.TradeCalculator;
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
        translationChecks();
        cryptoQuoteChecks();
        directMarketQuoteChecks();
        marketHoursChecks();
        storageChecks();
        tradingValidationChecks();
        marketTimeChecks();
        quoteScheduleChecks();
        System.out.println("FINANCE_SELF_TEST_OK");
    }

    private static void configChecks() throws Exception {
        Path directory = Files.createTempDirectory("imyvm-finance-config-");
        try {
            Path config = directory.resolve("imyvm_finance.properties");
            FinanceConfig defaults = FinanceConfig.load(config);
            check(Files.exists(config), "default config was not created");
            checkEquals(1000L, defaults.quoteConnectTimeout().toMillis(), "connect timeout");
            checkEquals(3L, defaults.quotePollIntervalMinutes(), "default refresh");
            checkEquals(15L, defaults.briefingIntervalMinutes(), "default briefing interval");
            checkEquals(20L, defaults.briefingDelaySeconds(), "default briefing delay");
            check(!defaults.setupInitialized(), "setup defaults incomplete");
            checkEquals("zh_cn", defaults.language(), "default language");
            check(defaults.briefingEnabled(), "default briefing enabled");
            checkEquals(15L * 60 * 1000, defaults.tradingRules().maxQuoteAgeMillis(), "default quote age");

            Properties properties = new Properties();
            properties.setProperty("market.connect-timeout-ms", "1500");
            properties.setProperty("market.read-timeout-ms", "3500");
            properties.setProperty("market.holidays.CN", "2026-08-24,invalid");
            properties.setProperty("quote.poll-interval-minutes", "7");
            properties.setProperty("quote.poll-delay-seconds", "19");
            properties.setProperty("briefing.interval-minutes", "30");
            properties.setProperty("briefing.delay-seconds", "22");
            properties.setProperty("trading.fee-bps", "25");
            properties.setProperty("trading.min-units", "2");
            try (var writer = Files.newBufferedWriter(config)) {
                properties.store(writer, "test");
            }

            FinanceConfig overridden = FinanceConfig.load(config);
            checkEquals(1500L, overridden.quoteConnectTimeout().toMillis(), "connect timeout");
            checkEquals(3500L, overridden.quoteReadTimeout().toMillis(), "read timeout");
            checkEquals(7L, overridden.quotePollIntervalMinutes(), "custom poll interval");
            checkEquals(19L, overridden.quotePollDelaySeconds(), "custom poll delay");
            checkEquals(30L, overridden.briefingIntervalMinutes(), "custom briefing interval");
            checkEquals(22L, overridden.briefingDelaySeconds(), "custom briefing delay");
            checkEquals(25, overridden.tradingRules().feeBps(), "custom fee");
            checkEquals(2L, overridden.tradingRules().minUnits(), "custom minimum units");
        } finally {
            deleteTree(directory);
        }
    }

    private static void translationChecks() {
        Translator.setLanguage("zh_cn");
        String label = MarketCommands.instrumentLabel(Instrument.CN_000001).getString();
        check(label.contains("上证指数") && label.contains("CN:000001"),
            "instrument label missing readable name or symbol: " + label);
        check(Instrument.fromSymbol("CN000001") == Instrument.CN_000001,
            "command-form symbol without colon did not resolve");
        check(Instrument.fromSymbol("cn:000001") == Instrument.CN_000001,
            "display-form symbol did not resolve");
        String rendered = Translator.tr("commands.market.list.item", "CN:000001", "上证指数", "开市").getString();
        check(rendered.contains("CN:000001") && rendered.contains("上证指数"),
            "zh_cn translation did not interpolate arguments: " + rendered);
        check(!rendered.contains("{0}") && !rendered.contains("imyvm_finance."),
            "zh_cn translation leaked placeholder or key: " + rendered);
        Translator.setLanguage("en_us");
        String english = Translator.tr("commands.market.list.item", "CN:000001", "SSE", "OPEN").getString();
        check(english.contains("CN:000001") && !english.contains("{0}"),
            "en_us translation did not interpolate arguments: " + english);
    }

    private static void cryptoQuoteChecks() {
        var snapshot = com.imyvm.finance.quote.CryptoQuoteClient.parseBinance(
            "[{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"60000\",\"priceChangePercent\":\"1.5\"},{\"symbol\":\"ETHUSDT\",\"lastPrice\":\"3000\",\"priceChangePercent\":\"-0.5\"}]",
            java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, snapshot.quotes().size(), "crypto quote count");
        checkEquals(com.imyvm.finance.market.Instrument.CRYPTO_BTC, snapshot.quotes().getFirst().instrument(), "crypto BTC instrument");
        checkEquals(600_000_000L, snapshot.quotes().getFirst().priceScaled(), "crypto BTC price");
        checkEquals(150L, snapshot.quotes().getFirst().changeBps(), "crypto BTC change");
        var kraken = com.imyvm.finance.quote.CryptoQuoteClient.parseKraken("{\"result\":{\"XXBTZUSD\":{\"c\":[\"60000\"],\"o\":\"59000\"},\"XETHZUSD\":{\"c\":[\"3000\"],\"o\":\"2900\"}}}", java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, kraken.quotes().size(), "Kraken quote count");
        var okx = com.imyvm.finance.quote.CryptoQuoteClient.parseOkx("{\"data\":[{\"instId\":\"BTC-USDT\",\"last\":\"60000\",\"open24h\":\"59000\"}]}", "{\"data\":[{\"instId\":\"ETH-USDT\",\"last\":\"3000\",\"open24h\":\"2900\"}]}", java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, okx.quotes().size(), "OKX quote count");
        var bybit = com.imyvm.finance.quote.CryptoQuoteClient.parseBybit("{\"result\":{\"list\":[{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"60000\",\"prevPrice24h\":\"59000\"}]}}", "{\"result\":{\"list\":[{\"symbol\":\"ETHUSDT\",\"lastPrice\":\"3000\",\"prevPrice24h\":\"2900\"}]}}", java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, bybit.quotes().size(), "Bybit quote count");
        var bitstamp = com.imyvm.finance.quote.CryptoQuoteClient.parseBitstamp("{\"last\":\"60000\",\"open\":\"59000\"}", "{\"last\":\"3000\",\"open\":\"2900\"}", java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, bitstamp.quotes().size(), "Bitstamp quote count");
    }

    private static void directMarketQuoteChecks() {
        var quote = com.imyvm.finance.quote.DirectMarketQuoteClient.parseYahoo(
            "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":\"3000\",\"previousClose\":\"3010\"}}]}}",
            com.imyvm.finance.market.Instrument.CN_000001);
        checkEquals(30_000_000L, quote.priceScaled(), "direct Yahoo price");
        var tencent = com.imyvm.finance.quote.DirectMarketQuoteClient.parseTencent(("v_s_sh000001=\"1~SSE~000001~3000~10~1.25~\";\nv_s_sz399001=\"1~SZ~399001~3000~10~1.25~\";\nv_s_sz399006=\"1~CY~399006~3000~10~1.25~\";\nv_s_sh000300=\"1~CSI~000300~3000~10~1.25~\";\nv_s_sh000905=\"1~CSI500~000905~3000~10~1.25~\";").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        checkEquals(5, tencent.size(), "Tencent quote count");
        checkEquals(-33L, quote.changeBps(), "direct Yahoo change");
        var status = new com.imyvm.finance.quote.DirectMarketQuoteClient(
            java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)).controlStatus();
        check(status.contains("lastSuccessfulProviders") && status.contains("providerStats") && status.contains("statsSince"),
            "provider status missing runtime monitoring fields: " + status);
    }

    private static void marketHoursChecks() {
        checkEquals(com.imyvm.finance.market.MarketStatus.OPEN,
            com.imyvm.finance.quote.MarketHours.status("CRYPTO", java.time.Instant.parse("2026-08-23T00:00:00Z")),
            "crypto market hours");
        checkEquals(com.imyvm.finance.market.MarketStatus.CLOSED,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-23T03:00:00Z")),
            "China weekend market hours");
        checkEquals(com.imyvm.finance.market.MarketStatus.CLOSED,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-24T03:00:00Z"),
                java.util.Set.of(java.time.LocalDate.of(2026, 8, 24))),
            "China configured holiday hours");
    }

    private static void tradingValidationChecks() throws Exception {
        TradeEstimate estimate = estimate(
            TradeSide.SELL, 1L, "same-price", 100L, 10L, 1L, 9L);
        StockPositionView position = new StockPositionView(
            UUID.randomUUID(), UUID.randomUUID(), Instrument.CN_000001,
            1L, 0L, "buy-snapshot", 100L, 0L);
        try {
            TradeValidator.validateSell(estimate, position, 100L, 101L, 0L, TradingRules.DEFAULT);
            throw new AssertionError("sell accepted without a later quote");
        } catch (TradeValidationException expected) {
            checkEquals("commands.market.trade.same_snapshot", expected.messageKey(), "later quote check");
        }
        TradeValidator.validateSell(estimate, position, 101L, 101L, 0L, TradingRules.DEFAULT);
    }

    private static void marketTimeChecks() throws Exception {
        var quote = new com.imyvm.finance.storage.StoredQuote(
            "same-price", "test", 2_000L, 1_000L,
            new com.imyvm.finance.market.MarketQuote(
                Instrument.CN_000001, "SSE", 10_000L, 0L, MarketStatus.OPEN));
        TradeCalculator.estimate(TradeSide.BUY, quote, 1L, 1_000L + TradingRules.DEFAULT.maxQuoteAgeMillis(),
            TradingRules.DEFAULT);
        try {
            TradeCalculator.estimate(TradeSide.BUY, quote, 1L, 1_001L + TradingRules.DEFAULT.maxQuoteAgeMillis(),
                TradingRules.DEFAULT);
            throw new AssertionError("unchanged market quote was treated as fresh");
        } catch (TradeValidationException expected) {
            checkEquals("commands.market.trade.quote_stale", expected.messageKey(), "market time freshness");
        }
    }

    private static void quoteScheduleChecks() {
        long delay = com.imyvm.finance.quote.QuoteRefreshService.millisecondsUntilPollNode(
            java.time.Instant.parse("2026-08-19T10:00:00Z"), java.time.ZoneOffset.UTC, 5, 17);
        checkEquals(77_000L, delay, "poll delay before hourly anchor");
        delay = com.imyvm.finance.quote.QuoteRefreshService.millisecondsUntilPollNode(
            java.time.Instant.parse("2026-08-19T10:01:18Z"), java.time.ZoneOffset.UTC, 5, 17);
        checkEquals(299_000L, delay, "poll delay after hourly anchor");
        check(com.imyvm.finance.ImyvmFinance.isBriefingPollNode(
            java.time.Instant.parse("2026-08-19T10:16:17Z"), java.time.ZoneOffset.UTC, 17, 15, 15),
            "briefing node did not align with delayed poll");
        check(com.imyvm.finance.ImyvmFinance.isBriefingPollNode(
            java.time.Instant.parse("2026-08-19T10:16:32Z"), java.time.ZoneOffset.UTC, 17, 15, 15),
            "briefing node rejected positive jitter");
        check(!com.imyvm.finance.ImyvmFinance.isBriefingPollNode(
            java.time.Instant.parse("2026-08-19T10:16:33Z"), java.time.ZoneOffset.UTC, 17, 15, 15),
            "briefing node accepted time outside jitter");
    }

    private static void storageChecks() throws Exception {
        Path directory = Files.createTempDirectory("imyvm-finance-storage-");
        StockTradingStore trading = null;
        StockTransactionStore transactions = null;
        try {
            Path database = directory.resolve("finance.db");
            trading = StockTradingStore.open(database);
            transactions = StockTransactionStore.open(database);

            check(trading.isGlobalTradingEnabled(), "global trading defaults enabled");
            check(trading.isTradingEnabled(Instrument.CN_000001), "instrument trading defaults enabled");
            trading.setGlobalTradingEnabled(false);
            trading.setTradingEnabled(Instrument.CN_000001, false);
            check(!trading.isGlobalTradingEnabled(), "global trading is persisted as halted");
            check(!trading.isTradingEnabled(Instrument.CN_000001), "instrument trading is persisted as halted");
            trading.setGlobalTradingEnabled(true);
            trading.setTradingEnabled(Instrument.CN_000001, true);
            check(trading.isGlobalTradingEnabled(), "global trading resumes");
            check(trading.isTradingEnabled(Instrument.CN_000001), "instrument trading resumes");

            UUID subscriber = UUID.randomUUID();
            check(!trading.isBriefingOptedOut(subscriber), "briefing defaults subscribed");
            trading.setBriefingOptedOut(subscriber, true);
            check(trading.isBriefingOptedOut(subscriber), "briefing opt-out persisted");
            check(trading.findBriefingOptOuts().contains(subscriber), "briefing opt-out listed");
            trading.setBriefingOptedOut(subscriber, true);
            trading.setBriefingOptedOut(subscriber, false);
            check(!trading.isBriefingOptedOut(subscriber), "briefing re-subscribed");
            check(trading.findBriefingOptOuts().isEmpty(), "briefing opt-out cleared");
            UUID alertPlayer = UUID.randomUUID();
            for (int index = 0; index < 11; index++)
                trading.enqueueMarketAlert("failed:market:" + index, index);
            var pendingAlerts = trading.findUndeliveredMarketAlerts(alertPlayer);
            checkEquals(10, pendingAlerts.size(), "market alert retention");
            checkEquals("failed:market:1", pendingAlerts.getFirst().alert(), "market alert oldest retained");
            trading.markMarketAlertDelivered(alertPlayer, pendingAlerts.getFirst().id());
            checkEquals(9, trading.findUndeliveredMarketAlerts(alertPlayer).size(), "market alert receipt");

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

            UUID heldOrderId = UUID.randomUUID();
            UUID heldTransactionId = UUID.randomUUID();
            StockTransaction heldSell = transaction(
                heldTransactionId, player, StockOperation.SELL, heldOrderId, 5_928L, 8L);
            TradeEstimate heldEstimate =
                estimate(TradeSide.SELL, 60L, "held-snapshot", 9_900L, 5_940L, 12L, 5_928L);
            transactions.createPrepared(heldSell);
            trading.createPendingSell(
                heldOrderId, UUID.randomUUID(), positionId, heldSell, heldEstimate, 8L);
            checkEquals(6_000L, trading.positionValue(player),
                "frozen position still counts toward exposure");

            transactions.pruneBefore(7L);
            check(transactions.find(sellTransactionId).isEmpty(), "completed transaction pruned");
            check(transactions.find(manualTransactionId).isPresent(), "recent cancelled transaction retained");
            check(transactions.find(heldTransactionId).isPresent(), "prepared transaction retained");
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
