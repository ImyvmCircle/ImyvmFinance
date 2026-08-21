package com.imyvm.finance;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.QuoteSnapshot;
import com.imyvm.finance.market.MarketCommands;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.storage.StockTradingStore;
import com.imyvm.finance.storage.StockTransactionStore;
import com.imyvm.finance.storage.QuoteSnapshotStore;
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
import java.util.List;
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
        simulationChecks();
        simulationFormulaChecks();
        directMarketQuoteChecks();
        marketHoursChecks();
        storageChecks();
        tradingValidationChecks();
        economyAmountChecks();
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
            checkEquals(12L, defaults.quoteIdlePollIntervalMinutes(), "default idle refresh");
            checkEquals(15L, defaults.briefingIntervalMinutes(), "default briefing interval");
            checkEquals(20L, defaults.briefingDelaySeconds(), "default briefing delay");
            checkEquals(15L, defaults.quoteProviderBackoffMinutes(), "default provider backoff");
            check(!defaults.setupInitialized(), "setup defaults incomplete");
            checkEquals("zh_cn", defaults.language(), "default language");
            checkEquals("Asia/Shanghai", defaults.timeZone(), "default time zone");
            checkEquals(30_000_000L, defaults.simulationDefaultPrices().get("CN:000001"), "default simulation point");
            checkEquals(600_000_000L, defaults.simulationDefaultPrices().get("CRYPTO:BTCUSDT"), "default crypto simulation point");
            check(defaults.briefingEnabled(), "default briefing enabled");
            checkEquals(15L * 60 * 1000, defaults.tradingRules().maxQuoteAgeMillis(), "default quote age");

            Properties properties = new Properties();
            properties.setProperty("market.connect-timeout-ms", "1500");
            properties.setProperty("market.read-timeout-ms", "3500");
            properties.setProperty("market.holidays.CN", "2026-08-24,invalid");
            properties.setProperty("time-zone", "Asia/Tokyo");
            properties.setProperty("quote.poll-interval-minutes", "7");
            properties.setProperty("quote.idle-poll-interval-minutes", "11");
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
            checkEquals("Asia/Tokyo", overridden.timeZone(), "custom time zone");
            checkEquals("Asia/Tokyo", overridden.zoneId().getId(), "custom zone id");
            checkEquals(7L, overridden.quotePollIntervalMinutes(), "custom poll interval");
            checkEquals(11L, overridden.quoteIdlePollIntervalMinutes(), "custom idle poll interval");
            checkEquals(19L, overridden.quotePollDelaySeconds(), "custom poll delay");
            checkEquals(30L, overridden.briefingIntervalMinutes(), "custom briefing interval");
            checkEquals(22L, overridden.briefingDelaySeconds(), "custom briefing delay");
            checkEquals(25, overridden.tradingRules().feeBps(), "custom fee");
            checkEquals(2L, overridden.tradingRules().minUnits(), "custom minimum units");
            check(idleRelationChecks(), "idle relation checks");
        } finally {
            deleteTree(directory);
        }
    }

    private static void translationChecks() {
        Translator.setLanguage("zh_cn");
        String label = MarketCommands.instrumentLabel(Instrument.CN_000001).getString();
        check(label.contains("上证指数") && label.contains("CN:000001"),
            "instrument label missing readable name or symbol: " + label);
        check(MarketCommands.instrumentLabel(Instrument.CRYPTO_BTC).getString().contains("中小本"),
            "Chinese BTC instrument name was not localized");
        check(MarketCommands.instrumentLabel(Instrument.CRYPTO_ETH).getString().contains("煤气罐"),
            "Chinese ETH instrument name was not localized");
        check(Translator.tr("commands.market.disclaimer").getString().contains("[提示]")
            && Translator.tr("commands.market.disclaimer").getString().contains("游戏内容仅供娱乐")
            && Translator.tr("commands.market.briefing.buy_hint").getString().contains("点击拟定买入"),
            "Chinese player notice translation missing");
        Translator.setLanguage("en_us");
        check(Translator.tr("commands.market.disclaimer").getString().contains("[Notice]")
            && Translator.tr("commands.market.briefing.buy_hint").getString().contains("Click to prepare a purchase"),
            "English player notice translation missing");
        Translator.setLanguage("zh_cn");
        String briefingHeader = Translator.tr("commands.market.briefing.header", "2026-08-19 20:00:00 +08:00 Asia/Taipei").getString();
        check(briefingHeader.contains("2026-08-19 20:00:00") && !briefingHeader.contains("{0}"),
            "briefing timestamp was not interpolated: " + briefingHeader);
        check(Instrument.fromSymbol("CN000001") == Instrument.CN_000001,
            "command-form symbol without colon did not resolve");
        check(Instrument.fromSymbol("cn:000001") == Instrument.CN_000001,
            "display-form symbol did not resolve");
        String rendered = Translator.tr("commands.market.list.item", "CN:000001", "3000", "1.25%", "37.50", "2990", "日内", "可交易").getString();
        check(rendered.contains("CN:000001") && rendered.contains("3000") && rendered.contains("1.25%") && rendered.contains("2990"),
            "zh_cn translation did not interpolate arguments: " + rendered);
        check(!rendered.contains("{0}") && !rendered.contains("imyvm_finance."),
            "zh_cn translation leaked placeholder or key: " + rendered);
        Translator.setLanguage("en_us");
        String english = Translator.tr("commands.market.list.item", "CN:000001", "3000", "1.25%", "37.50", "2990", "intraday", "TRADABLE").getString();
        check(english.contains("CN:000001") && english.contains("3000") && english.contains("2990") && !english.contains("{0}"),
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
        var mexc = com.imyvm.finance.quote.CryptoQuoteClient.parseMexc(
            "{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"60000\",\"openPrice\":\"59000\"}",
            "{\"symbol\":\"ETHUSDT\",\"lastPrice\":\"3000\",\"openPrice\":\"2900\"}",
            java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, mexc.quotes().size(), "MEXC quote count");
        var bitget = com.imyvm.finance.quote.CryptoQuoteClient.parseBitget(
            "{\"data\":[{\"symbol\":\"BTCUSDT\",\"lastPr\":\"60000\",\"open\":\"59000\"}]}",
            "{\"data\":[{\"symbol\":\"ETHUSDT\",\"lastPr\":\"3000\",\"open\":\"2900\"}]}",
            java.time.Instant.ofEpochMilli(1000));
        checkEquals(2, bitget.quotes().size(), "Bitget quote count");
        checkWarning(() -> com.imyvm.finance.quote.CryptoQuoteClient.parseBinance("{\"code\":-1003}", java.time.Instant.ofEpochMilli(1000)), "Binance warning code");
        checkWarning(() -> com.imyvm.finance.quote.CryptoQuoteClient.parseBitget("{\"code\":\"40001\",\"msg\":\"invalid request\"}", "{\"code\":\"00000\",\"data\":[{\"lastPr\":\"3000\",\"open\":\"2900\"}]}", java.time.Instant.ofEpochMilli(1000)), "Bitget warning code");
    }

    private static void checkWarning(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label + " did not fail");
        } catch (IllegalStateException exception) {
            check(exception.getMessage().startsWith("provider warning:"), label + " was not classified as provider warning: " + exception.getMessage());
        }
    }

    private static void simulationFormulaChecks() {
        check(com.imyvm.finance.quote.SimulationFormula.compile(com.imyvm.finance.quote.SimulationFormula.DEFAULT) != null, "default simulation formula did not compile");
        check(com.imyvm.finance.quote.SimulationFormula.parse("LN(10) + LOG10(100) + LOG2(8) + LOGN(16, 2)") != null, "logarithm formula did not compile");
        check(com.imyvm.finance.quote.SimulationFormula.parse(com.imyvm.finance.quote.SimulationFormula.STABLE) != null, "stable preset formula did not compile");
        var rising = com.imyvm.finance.quote.SimulatedQuoteGenerator.nextStep(Instrument.CN_000001, new MarketQuote(Instrument.CN_000001, "SSE", 30_000_000L, 0, MarketStatus.OPEN), 7L, 1, 5, 0, com.imyvm.finance.quote.SimulationFormula.DEFAULT);
        var falling = com.imyvm.finance.quote.SimulatedQuoteGenerator.nextStep(Instrument.CN_000001, new MarketQuote(Instrument.CN_000001, "SSE", 30_000_000L, 0, MarketStatus.OPEN), 7L, 1, 1, 0, com.imyvm.finance.quote.SimulationFormula.DEFAULT);
        check(rising.trendBps() > falling.trendBps(), "trend factor did not control long-term direction");
        check(rising.quote().priceScaled() != falling.quote().priceScaled(), "trend factor did not affect simulation price");
        try { com.imyvm.finance.quote.SimulationFormula.parse("LN(-1)"); throw new AssertionError("invalid logarithm formula was accepted"); }
        catch (IllegalArgumentException expected) { }
        try { com.imyvm.finance.quote.SimulationFormula.parse("LOGN(10, 1)"); throw new AssertionError("invalid logarithm base was accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void directMarketQuoteChecks() throws Exception {
        var quote = com.imyvm.finance.quote.DirectMarketQuoteClient.parseYahoo(
            "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":\"3000\",\"previousClose\":\"3010\"}}]}}",
            com.imyvm.finance.market.Instrument.CN_000001);
        checkEquals(30_000_000L, quote.priceScaled(), "direct Yahoo price");
        var tencent = com.imyvm.finance.quote.DirectMarketQuoteClient.parseTencent(("v_s_sh000001=\"1~SSE~000001~3000~10~1.25~\";\nv_s_sz399001=\"1~SZ~399001~3000~10~1.25~\";\nv_s_sz399006=\"1~CY~399006~3000~10~1.25~\";\nv_s_sh000300=\"1~CSI~000300~3000~10~1.25~\";\nv_s_sh000905=\"1~CSI500~000905~3000~10~1.25~\";").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        checkEquals(5, tencent.size(), "Tencent quote count");
        checkEquals(-33L, quote.changeBps(), "direct Yahoo change");
        var sina = com.imyvm.finance.quote.DirectMarketQuoteClient.parseSina((
            "var hq_str_s_sh000001=\"SSE,3000,12,0.40,1,2\";"
                + "var hq_str_s_sz399001=\"SZ,14000,20,0.14,1,2\";"
                + "var hq_str_s_sz399006=\"CY,3500,-10,-0.29,1,2\";"
                + "var hq_str_s_sh000300=\"CSI,4600,15,0.33,1,2\";"
                + "var hq_str_s_sh000905=\"CSI500,7800,0,0.00,1,2\";").getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            java.time.Instant.EPOCH);
        checkEquals(30_000_000L, sina.get(Instrument.CN_000001).priceScaled(), "Sina current price");
        checkEquals(40L, sina.get(Instrument.CN_000001).changeBps(), "Sina change percent");
        var status = new com.imyvm.finance.quote.DirectMarketQuoteClient(
            java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)).controlStatus();
        check(status.contains("lastSuccessfulProviders") && status.contains("providerStats") && status.contains("statsSince"),
            "provider status missing runtime monitoring fields: " + status);

        var singleProvider = new com.imyvm.finance.quote.DirectMarketQuoteClient(
            java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), java.util.Map.of(),
            java.util.Map.of("CN", true, "CRYPTO", false), java.util.Set.of(),
            java.util.Map.of("CN", java.util.List.of("only")), 1);
        var recordAttempt = com.imyvm.finance.quote.DirectMarketQuoteClient.class
            .getDeclaredMethod("recordAttempt", String.class);
        var recordFailure = com.imyvm.finance.quote.DirectMarketQuoteClient.class
            .getDeclaredMethod("recordFailure", String.class, Exception.class);
        var immediateFailure = com.imyvm.finance.quote.DirectMarketQuoteClient.class
            .getDeclaredMethod("recordFailure", String.class, Exception.class, boolean.class);
        var providersForAttempt = com.imyvm.finance.quote.DirectMarketQuoteClient.class
            .getDeclaredMethod("providersForAttempt", String.class);
        var isBackedOff = com.imyvm.finance.quote.DirectMarketQuoteClient.class
            .getDeclaredMethod("isBackedOff", String.class);
        recordAttempt.setAccessible(true);
        recordFailure.setAccessible(true);
        immediateFailure.setAccessible(true);
        providersForAttempt.setAccessible(true);
        isBackedOff.setAccessible(true);
        recordAttempt.invoke(singleProvider, "CN:only");
        recordFailure.invoke(singleProvider, "CN:only", new Exception("first"));
        check(!(Boolean) isBackedOff.invoke(singleProvider, "CN:only"),
            "provider entered backoff after one failure");
        var outages = com.imyvm.finance.quote.DirectMarketQuoteClient.class
            .getDeclaredField("probingMarkets");
        outages.setAccessible(true);
        ((java.util.Set<String>) outages.get(singleProvider)).add("CN");
        checkEquals(java.util.List.of("only"), providersForAttempt.invoke(singleProvider, "CN"),
            "single provider should retry itself after one failure");
        recordFailure.invoke(singleProvider, "CN:only", new Exception("second"));
        immediateFailure.invoke(singleProvider, "CN:warning", new Exception("provider warning: binance code=-1003"), true);
        check((Boolean) isBackedOff.invoke(singleProvider, "CN:warning"),
            "provider warning did not enter backoff immediately");
        check(((String) singleProvider.controlStatus()).contains("\"backoffSecondsRemaining\":"),
            "provider did not enter backoff after two failures");

        Translator.setLanguage("zh_cn");
        var providerStatus = MarketCommands.class.getDeclaredMethod("providerStatus", com.google.gson.JsonObject.class, String.class, com.google.gson.JsonObject.class);
        providerStatus.setAccessible(true);
        var root = new com.google.gson.JsonObject();
        var unused = new com.google.gson.JsonObject();
        unused.addProperty("requests", 0);
        check(((net.minecraft.network.chat.Component) providerStatus.invoke(null, root, "CN:only", unused)).getString().contains("尚未使用"),
            "unused provider state was not rendered");
        var successful = new com.google.gson.JsonObject();
        successful.addProperty("requests", 1);
        successful.addProperty("lastSuccessAt", "2026-08-21T00:00:01Z");
        check(((net.minecraft.network.chat.Component) providerStatus.invoke(null, root, "CN:only", successful)).getString().contains("当前可用"),
            "last successful provider was not rendered as available");
        var failed = new com.google.gson.JsonObject();
        failed.addProperty("requests", 1);
        failed.addProperty("lastFailureAt", "2026-08-21T00:00:01Z");
        failed.addProperty("backoffSecondsRemaining", 60);
        check(((net.minecraft.network.chat.Component) providerStatus.invoke(null, root, "CN:only", failed)).getString().contains("退避中"),
            "backed off provider state was not rendered");
        failed.addProperty("backoffSecondsRemaining", 0);
        check(((net.minecraft.network.chat.Component) providerStatus.invoke(null, root, "CN:only", failed)).getString().contains("等待下次重试"),
            "failed provider outside backoff was not rendered");
        var disabled = new com.google.gson.JsonArray();
        disabled.add("CN:only");
        root.add("disabledProviders", disabled);
        check(((net.minecraft.network.chat.Component) providerStatus.invoke(null, root, "CN:only", successful)).getString().contains("已停用"),
            "disabled provider state did not take priority");
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
        checkEquals(com.imyvm.finance.market.MarketStatus.OPEN,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-19T01:30:00Z")),
            "China morning opening boundary");
        checkEquals(com.imyvm.finance.market.MarketStatus.OPEN,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-19T03:29:00Z")),
            "China morning closing boundary" );
        checkEquals(com.imyvm.finance.market.MarketStatus.CLOSED,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-19T03:30:00Z")),
            "China lunch break" );
        checkEquals(com.imyvm.finance.market.MarketStatus.OPEN,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-19T05:00:00Z")),
            "China afternoon opening boundary");
        checkEquals(com.imyvm.finance.market.MarketStatus.CLOSED,
            com.imyvm.finance.quote.MarketHours.status("CN", java.time.Instant.parse("2026-08-19T07:00:00Z")),
            "China closing boundary");
    }

    private static boolean idleRelationChecks() {
        return ImyvmFinance.isQuoteIdleEligible(true, false, false)
            && ImyvmFinance.isQuoteIdleEligible(false, true, false)
            && !ImyvmFinance.isQuoteIdleEligible(true, false, true)
            && !ImyvmFinance.isInactiveForIdle(1800L, 1800L)
            && !ImyvmFinance.isInactiveForIdle(3600L, 1799L)
            && ImyvmFinance.isInactiveForIdle(3600L, 1800L)
            && !ImyvmFinance.isQuoteIdleEligible(false, false, false);
    }

    private static void economyAmountChecks() {
        checkEquals(12300L, com.imyvm.finance.economy.StockEconomySettlement.toEconomyAmount(123L), "economy minor-unit conversion");
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
        TradeCalculator.estimate(TradeSide.BUY, quote, 1L, 2_000L + TradingRules.DEFAULT.maxQuoteAgeMillis(), TradingRules.DEFAULT);
        try {
            TradeCalculator.estimate(TradeSide.BUY, quote, 1L, 2_001L + TradingRules.DEFAULT.maxQuoteAgeMillis(), TradingRules.DEFAULT);
            throw new AssertionError("stale quote was accepted");
        } catch (TradeValidationException expected) {
            checkEquals("commands.market.trade.quote_stale", expected.messageKey(), "stale quote check");
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
        check(com.imyvm.finance.quote.MarketHours.withinCloseWindow(
            "CN", java.time.Instant.parse("2026-08-19T06:56:00Z"), java.util.Set.of(), 5),
            "CN close window was not detected");
        check(!com.imyvm.finance.quote.MarketHours.withinCloseWindow(
            "CN", java.time.Instant.parse("2026-08-19T07:00:00Z"), java.util.Set.of(), 5),
            "CN close window continued after close");
        check(!com.imyvm.finance.quote.MarketHours.withinCloseWindow(
            "CN", java.time.Instant.parse("2026-08-19T06:54:59Z"), java.util.Set.of(), 5),
            "CN close window started too early");
        check(com.imyvm.finance.quote.MarketHours.withinCloseWindow(
            "CN", java.time.Instant.parse("2026-08-19T06:55:00Z"), java.util.Set.of(), 5),
            "CN close window start boundary");
        check(com.imyvm.finance.quote.MarketHours.withinCloseWindow(
            "CN", java.time.Instant.parse("2026-08-19T06:59:59Z"), java.util.Set.of(), 5),
            "CN close window end boundary");
        check(!com.imyvm.finance.quote.MarketHours.withinCloseWindow(
            "CRYPTO", java.time.Instant.parse("2026-08-19T06:56:00Z"), java.util.Set.of(), 5),
            "crypto market incorrectly used CN close window");
    }

    private static void storageChecks() throws Exception {
        Path directory = Files.createTempDirectory("imyvm-finance-storage-");
        StockTradingStore trading = null;
        StockTransactionStore transactions = null;
        QuoteSnapshotStore quotes = null;
        try {
            Path database = directory.resolve("finance.db");
            Class.forName("org.sqlite.JDBC");
            try (var legacy = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = legacy.createStatement()) {
                statement.execute("CREATE TABLE simulation_sessions (session_id INTEGER PRIMARY KEY, market TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER, function_id TEXT NOT NULL, seed INTEGER NOT NULL, status TEXT NOT NULL)");
                statement.execute("CREATE TABLE simulation_nodes (session_id INTEGER NOT NULL, node_time INTEGER NOT NULL, symbol TEXT NOT NULL, previous_price INTEGER NOT NULL, fluctuation_bps INTEGER NOT NULL, new_price INTEGER NOT NULL, PRIMARY KEY (session_id, node_time, symbol))");
            }
            trading = StockTradingStore.open(database);
            transactions = StockTransactionStore.open(database);
            quotes = QuoteSnapshotStore.open(database);
            for (int index = 0; index < 5; index++)
                quotes.save(new QuoteSnapshot("ma5-" + index, "test", index + 1L, index + 1L,
                    List.of(new MarketQuote(Instrument.CN_000001, "SSE", 10_000L + index * 100L, 0L, MarketStatus.OPEN)), List.of()));
            quotes.save(new QuoteSnapshot("node-time", "test", 0L, 0L,
                List.of(new MarketQuote(Instrument.CN_000001, "SSE", 10_500L, 0L, MarketStatus.OPEN)), List.of(), 8L));
            checkEquals(8L, quotes.find(Instrument.CN_000001, "node-time").orElseThrow().nodeTimeEpochMillis(),
                "snapshot node time");
            var recentPrices = quotes.findRecentPrices(Instrument.CN_000001, 5);
            checkEquals(5, recentPrices.size(), "MA5 history count");
            checkEquals(10_400L, recentPrices.getFirst(), "MA5 newest price");
            quotes.beginSimulation(123L, "CN", 100L, "robust_seeded_walk", com.imyvm.finance.quote.SimulationFormula.DEFAULT, 9L, 180_000L, 45_000L);
            quotes.recordSimulationNode(123L, 200L, "CN:000001", "test", 10_000L, 100L, 10_100L);
            var session = quotes.findSimulationSession(123L).orElseThrow();
            check(session.sessionUuid() != null && !session.sessionUuid().isBlank(), "simulation session UUID was not migrated or persisted");
            checkEquals(180_000L, session.intervalMillis(), "simulation interval was not persisted");
            checkEquals(45_000L, session.intervalToleranceMillis(), "simulation interval tolerance was not persisted");
            quotes.setSimulationFactor("CN:000001", 5);
            checkEquals(5, quotes.simulationFactor("CN:000001"), "simulation factor was not persisted");
            checkEquals(5, quotes.simulationFactorForSession(123L, "CN:000001", 5), "session factor was not frozen");
            checkEquals(5, quotes.simulationFactorForSession(123L, "CN:000001", 1), "session factor changed after freeze");
            quotes.saveSimulationState(123L, "CN:000001", 2.5, 7);
            checkEquals(7, quotes.findSimulationState(123L, "CN:000001").orElseThrow().iteration(), "simulation trend state was not persisted");
            var node = quotes.findSimulationNodes(123L, 10, 0).getFirst();
            check(Math.abs(node.logReturn() - Math.log(10_100.0 / 10_000.0)) < 1.0e-12, "simulation log return was not persisted from actual prices");
            var simulationSessions = new java.util.HashMap<String, Long>();
            var coldStart = com.imyvm.finance.quote.SimulationSnapshotBuilder.build(null, quotes, 1_000L, 180_000L, 7L,
                FinanceConfig.defaults().simulationDefaultPrices(), simulationSessions, FinanceConfig.defaults().marketHolidays()).orElseThrow();
            check(coldStart.quotes().stream().anyMatch(quote -> quote.instrument() == Instrument.CRYPTO_BTC && quote.priceScaled() > 0),
                "simulation did not use configured default point for a missing last quote");
            checkEquals(MarketStatus.CLOSED, coldStart.quotes().stream().filter(quote -> quote.instrument() == Instrument.CN_000001).findFirst().orElseThrow().status(),
                "cold-start China simulation opened outside market hours");
            check(coldStart.quotes().stream().allMatch(quote -> quote.origin() == com.imyvm.finance.market.QuoteOrigin.SIMULATED),
                "cold-start simulation leaked a real quote origin");
            long cnSimulation = simulationSessions.get("CN");
            long cryptoSimulation = simulationSessions.get("CRYPTO");
            com.imyvm.finance.quote.SimulationSnapshotBuilder.build(new QuoteSnapshot("partial", "test", 2_000L, 2_000L,
                List.of(new MarketQuote(Instrument.CRYPTO_BTC, "BTC", 600_000_000L, 0L, MarketStatus.OPEN)),
                List.of("failed:market:CN")), quotes, 2_000L, 180_000L, 7L,
                FinanceConfig.defaults().simulationDefaultPrices(), simulationSessions, FinanceConfig.defaults().marketHolidays()).orElseThrow();
            checkEquals("RECOVERED", quotes.findSimulationSession(cryptoSimulation).orElseThrow().status(),
                "recovered market simulation session was not finished");
            quotes.abortSimulation(cnSimulation, 3_000L);
            var abortedSimulation = quotes.findSimulationSession(cnSimulation).orElseThrow();
            checkEquals("ABORTED", abortedSimulation.status(), "stopped simulation session was not aborted");
            checkEquals(3_000L, abortedSimulation.endedAt(), "stopped simulation end time was not persisted");

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
            checkEquals(12L, active.buyFee(), "remaining buy fee");
            checkEquals(-56L, trading.realizedProfit(player), "realized partial-sale profit");

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
            if (quotes != null)
                quotes.close();
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
    private static void simulationChecks() {
        var previous = new MarketQuote(Instrument.CN_000001, "SSE", 30_000_000L, 0, MarketStatus.OPEN);
        var first = com.imyvm.finance.quote.SimulatedQuoteGenerator.nextStep(Instrument.CN_000001, previous, 7L, 1, 5, 0, com.imyvm.finance.quote.SimulationFormula.DEFAULT);
        var second = com.imyvm.finance.quote.SimulatedQuoteGenerator.nextStep(Instrument.CN_000001, previous, 7L, 1, 5, 0, com.imyvm.finance.quote.SimulationFormula.DEFAULT);
        checkEquals(first.quote().priceScaled(), second.quote().priceScaled(), "simulation seed reproducibility");
        check(first.quote().origin() == com.imyvm.finance.market.QuoteOrigin.SIMULATED, "simulation origin was not recorded");
        var falling = com.imyvm.finance.quote.SimulatedQuoteGenerator.nextStep(Instrument.CN_000001, previous, 7L, 1, 1, 0, com.imyvm.finance.quote.SimulationFormula.DEFAULT);
        check(first.trendBps() > falling.trendBps(), "trend factor did not control long-term direction");
        check(first.quote().priceScaled() != falling.quote().priceScaled(), "trend factor did not affect simulation price");
        check(com.imyvm.finance.quote.SimulatedQuoteGenerator.intervalToleranceMillis(180_000L) == 45_000L, "simulation interval tolerance changed unexpectedly");
    }

}
