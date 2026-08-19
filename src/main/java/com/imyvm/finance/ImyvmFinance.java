package com.imyvm.finance;

import com.imyvm.finance.market.MarketCommands;
import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.storage.StockTransactionStore;
import com.imyvm.finance.storage.StockTradingStore;
import com.imyvm.finance.storage.StoredQuote;
import com.imyvm.finance.storage.StoredOrder;
import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketStatus;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;
import com.imyvm.finance.economy.StockEconomySettlement;
import com.imyvm.finance.quote.QuoteRefreshService;
import com.imyvm.finance.quote.DirectMarketQuoteClient;
import com.imyvm.finance.quote.MarketHours;
import com.imyvm.finance.market.QuoteSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ImyvmFinance implements ModInitializer {
    private static final long IDLE_INACTIVITY_THRESHOLD_SECONDS = 1800L;
    private static final long IDLE_STARTUP_GRACE_SECONDS = 3600L;
    public static final String MOD_ID = "imyvm_finance";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final AtomicBoolean SETUP_CHECKING = new AtomicBoolean();
    public static QuoteSnapshotStore QUOTE_STORE;
    public static FinanceConfig CONFIG = FinanceConfig.defaults();
    public static com.imyvm.finance.trading.TradingRules TRADING_RULES = CONFIG.tradingRules();
    public static StockTransactionStore TRANSACTION_STORE;
    public static StockTradingStore TRADING_STORE;
    public static StockEconomySettlement ECONOMY_SETTLEMENT;
    public static QuoteRefreshService QUOTE_REFRESHER;
    private static final long RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static long nextRetentionCleanupAt;
    private static long nextBriefingAt = Long.MAX_VALUE;
    private static String lastBriefingSnapshotId;
    private static long lastBriefingSentAt;
    private static long startupAnnouncementSentAt;
    private static final Map<String, MarketStatus> MARKET_STATUSES = new HashMap<>();
    private static final Map<String, Long> MARKET_EVENT_AT = new HashMap<>();
    private static volatile net.minecraft.server.MinecraftServer SERVER;
    private static long serverStartedAt;
    private static long lastMarketActivityAt;
    private static Path CONFIG_PATH;

    @Override
    public void onInitialize() {
        try {
            Path configPath = FabricLoader.getInstance().getConfigDir()
                .resolve(MOD_ID + ".properties");
            CONFIG_PATH = configPath;
            CONFIG = FinanceConfig.load(configPath);
            TRADING_RULES = CONFIG.tradingRules();
            Translator.setLanguage(CONFIG.language());
            Path databasePath = FabricLoader.getInstance().getGameDir()
                .resolve(MOD_ID)
                .resolve("finance.db");
            QUOTE_STORE = QuoteSnapshotStore.open(databasePath);
            TRANSACTION_STORE = StockTransactionStore.open(databasePath);
            TRADING_STORE = StockTradingStore.open(databasePath);
            ECONOMY_SETTLEMENT = new StockEconomySettlement(TRANSACTION_STORE);
        } catch (Exception exception) {
            LOGGER.error("Finance configuration or storage is unavailable", exception);
            CONFIG = FinanceConfig.defaults();
            TRADING_RULES = CONFIG.tradingRules();
        }

        CommandRegistrationCallback.EVENT.register(MarketCommands::register);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            serverStartedAt = System.currentTimeMillis();
            lastMarketActivityAt = serverStartedAt;
            MARKET_STATUSES.clear();
            MARKET_EVENT_AT.clear();
            lastBriefingSentAt = 0;
            startupAnnouncementSentAt = 0;
            recoverInterruptedTransactions();
            pruneExpiredData();
            nextBriefingAt = Long.MAX_VALUE;
            lastBriefingSnapshotId = null;
            if (CONFIG.setupInitialized()) {
                sendStartupAnnouncement(server);
                startQuoteRefresh();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            updateQuoteScheduler(server);
            pruneExpiredData();
            sendMarketBriefing(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!CONFIG.setupInitialized()
                && handler.getPlayer().createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                handler.getPlayer().sendSystemMessage(Translator.tr("commands.market.setup.required"));
            if (CONFIG.setupInitialized())
                handler.getPlayer().sendSystemMessage(playerMessage(Translator.tr("commands.market.notice.startup")));
            notifyPendingSettlement(handler.getPlayer());
            notifyPendingMarketAlerts(handler.getPlayer());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> closeQuoteStore());
        LOGGER.info("Initializing Imyvm Finance");
    }

    private static void recoverInterruptedTransactions() {
        if (TRANSACTION_STORE == null || TRADING_STORE == null)
            return;
        try {
            for (var transaction : TRANSACTION_STORE.findInterruptedTransactions())
                recoverInterruptedTransaction(transaction, TRADING_STORE.findOrder(transaction.transactionId()).orElse(null));
        } catch (Exception exception) {
            LOGGER.error("Failed to recover interrupted finance transactions", exception);
        }
    }

    private static void recoverInterruptedTransaction(
        com.imyvm.finance.transaction.StockTransaction transaction,
        StoredOrder order
    ) throws Exception {
        long now = System.currentTimeMillis();
        if (order == null) {
            TRANSACTION_STORE.markPending(
                transaction.transactionId(), "startup_recovery", "missing_order", null, now);
            return;
        }
        if (transaction.state() == com.imyvm.finance.transaction.StockTransactionState.PREPARED) {
            markOrderPendingManual(transaction, order);
            TRANSACTION_STORE.markPending(
                transaction.transactionId(), "startup_recovery", "economy_unconfirmed", null, now);
            return;
        }
        if (order.state() == com.imyvm.finance.trading.StockOrderState.PENDING_FINANCE)
            activateOrder(transaction, order);
        if (order.state() == com.imyvm.finance.trading.StockOrderState.PENDING_MANUAL)
            return;
        TRANSACTION_STORE.transition(
            transaction.transactionId(),
            com.imyvm.finance.transaction.StockTransactionState.FINANCE_CONFIRMED,
            "finance_recovered", now);
    }

    private static void activateOrder(com.imyvm.finance.transaction.StockTransaction transaction,
                                      StoredOrder order) throws Exception {
        if (transaction.operation() == com.imyvm.finance.transaction.StockOperation.BUY)
            TRADING_STORE.activateBuy(transaction.transactionId());
        else if (order.positionId() != null)
            TRADING_STORE.activateSell(transaction.transactionId(), order.positionId(), order.units());
        else
            throw new IllegalStateException("sell order has no position");
    }

    private static void markOrderPendingManual(com.imyvm.finance.transaction.StockTransaction transaction,
                                               StoredOrder order) throws Exception {
        if (transaction.operation() == com.imyvm.finance.transaction.StockOperation.BUY)
            TRADING_STORE.markPendingManual(transaction.transactionId());
        else if (order.positionId() != null)
            TRADING_STORE.markSellPendingManual(transaction.transactionId(), order.positionId());
        else
            throw new IllegalStateException("sell order has no position");
    }

    private static void pruneExpiredData() {
        long now = System.currentTimeMillis();
        if (now < nextRetentionCleanupAt)
            return;
        nextRetentionCleanupAt = now + RETENTION_MILLIS;
        if (QUOTE_STORE == null || TRADING_STORE == null || TRANSACTION_STORE == null)
            return;
        try {
            long cutoff = now - RETENTION_MILLIS;
            QUOTE_STORE.pruneBefore(cutoff);
            TRANSACTION_STORE.pruneBefore(cutoff);
            TRADING_STORE.pruneBefore(cutoff);
        } catch (Exception exception) {
            LOGGER.error("Failed to prune expired finance data", exception);
        }
    }

    private static void notifyPendingSettlement(net.minecraft.server.level.ServerPlayer player) {
        if (!CONFIG.setupInitialized() || TRANSACTION_STORE == null)
            return;
        try {
            int count = TRANSACTION_STORE.pendingSettlementCount(player.getUUID());
            if (count > 0)
                player.sendSystemMessage(playerMessage(Translator.tr("commands.market.pending.player_notice", count)));
        } catch (Exception exception) {
            LOGGER.error("Failed to read pending finance settlements for {}", player.getUUID(), exception);
        }
    }

    private static void notifyPendingMarketAlerts(net.minecraft.server.level.ServerPlayer player) {
        if (!CONFIG.setupInitialized()
            || TRADING_STORE == null
            || !player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            return;
        try {
            for (StockTradingStore.MarketAlert alert : TRADING_STORE.findUndeliveredMarketAlerts(player.getUUID())) {
                player.sendSystemMessage(playerMessage(marketAlertMessage(alert.alert())));
                TRADING_STORE.markMarketAlertDelivered(player.getUUID(), alert.id());
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to deliver pending market alerts for {}", player.getUUID(), exception);
        }
    }

    private static void sendMarketBriefing(net.minecraft.server.MinecraftServer server) {
        if (!CONFIG.setupInitialized())
            return;
        long now = System.currentTimeMillis();
        if (now < nextBriefingAt || QUOTE_STORE == null || TRADING_STORE == null)
            return;
        nextBriefingAt = Long.MAX_VALUE;
        if (!CONFIG.briefingEnabled())
            return;
        Instrument[] instruments = Instrument.values();
        java.util.List<StoredQuote> quotes = new java.util.ArrayList<>();
        for (Instrument instrument : instruments) {
            try {
                quotes.add(QUOTE_STORE.findLatest(instrument).orElse(null));
            } catch (Exception exception) {
                LOGGER.warn("Failed to prepare market briefing for {}", instrument.symbol(), exception);
                quotes.add(null);
            }
        }
        StoredQuote leader = null;
        StoredQuote loser = null;
        for (StoredQuote quote : quotes) {
            if (quote == null)
                continue;
            if (leader == null || quote.quote().changeBps() > leader.quote().changeBps())
                leader = quote;
            if (loser == null || quote.quote().changeBps() < loser.quote().changeBps())
                loser = quote;
        }
        boolean markMovers = leader != null && loser != null
            && leader.quote().changeBps() != loser.quote().changeBps();
        boolean hasOpenMarket = quotes.stream().anyMatch(quote -> quote != null
            && quote.quote().status() == MarketStatus.OPEN);
        if (!hasOpenMarket)
            return;
        MutableComponent briefing = Component.empty().append(Translator.tr("commands.market.briefing.header",
            formatLocalTimestamp(now)));
        for (String market : new String[]{"CN", "CRYPTO"}) {
            MutableComponent line = Component.empty().append("\n").append(Translator.tr("commands.market.briefing.market", marketLabel(market)));
            for (int index = 0; index < instruments.length; index++) {
                Instrument instrument = instruments[index];
                if (!instrument.market().equals(market))
                    continue;
                StoredQuote quote = quotes.get(index);
                if (quote == null)
                    continue;
                try {
                    boolean tradable = quote.quote().status() == MarketStatus.OPEN
                        && TRADING_STORE.isGlobalTradingEnabled() && TRADING_STORE.isTradingEnabled(instrument);
                    Component name = MarketCommands.instrumentLabel(instrument).copy();
                    if (tradable)
                        name = name.copy().withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand(
                                "/imyvm-market estimate " + instrument.commandForm() + " " + TRADING_RULES.minUnits()))
                            .withHoverEvent(new HoverEvent.ShowText(Translator.tr("commands.market.briefing.buy_hint")))
                            .withUnderlined(true));
                    Component mover = Component.empty();
                    if (markMovers && quote == leader)
                        mover = Translator.tr("commands.market.briefing.leader");
                    else if (markMovers && quote == loser)
                        mover = Translator.tr("commands.market.briefing.loser");
                    line.append(Translator.tr("commands.market.briefing.item", name,
                        formatPrice(quote.quote().priceScaled()), formatPercent(quote.quote().changeBps()),
                        briefingStatus(quote, tradable), mover));
                } catch (Exception exception) {
                    LOGGER.warn("Failed to prepare market briefing for {}", instrument.symbol(), exception);
                }
            }
            briefing.append(line);
        }
        briefing.append("\n").append(Translator.tr("commands.market.briefing.toggle_hint"));
        java.util.Set<java.util.UUID> briefingOptOuts;
        try {
            briefingOptOuts = TRADING_STORE.findBriefingOptOuts();
        } catch (Exception exception) {
            LOGGER.warn("Failed to load briefing opt-outs", exception);
            briefingOptOuts = java.util.Set.of();
        }
        lastBriefingSentAt = now;
        for (var player : server.getPlayerList().getPlayers()) {
            if (!briefingOptOuts.contains(player.getUUID()))
                player.sendSystemMessage(playerMessage(briefing));
        }
    }

    private static Component briefingStatus(StoredQuote quote, boolean tradable) {
        if (tradable)
            return Translator.tr("commands.market.briefing.status.open");
        if (quote.quote().status() == MarketStatus.OPEN)
            return Translator.tr("commands.market.briefing.status.paused");
        return Translator.tr("commands.market.briefing.status." + quote.quote().status().name().toLowerCase());
    }

    private static String formatLocalTimestamp(long epochMillis) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), MarketHours.CHINA_ZONE)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX z"));
    }

    private static String formatPrice(long priceScaled) {
        return java.math.BigDecimal.valueOf(priceScaled, 4).stripTrailingZeros().toPlainString();
    }

    private static String formatPercent(long changeBps) {
        return java.math.BigDecimal.valueOf(changeBps, 2).setScale(2).toPlainString() + "%";
    }

    private static void updateQuoteScheduler(net.minecraft.server.MinecraftServer server) {
        if (!CONFIG.setupInitialized() || QUOTE_REFRESHER == null || serverStartedAt == 0)
            return;
        long now = System.currentTimeMillis();
        boolean noPlayers = server.getPlayerList().getPlayers().isEmpty();
        boolean inactiveForHour = now - lastMarketActivityAt >= IDLE_INACTIVITY_THRESHOLD_SECONDS * 1000L
            && now - serverStartedAt >= IDLE_STARTUP_GRACE_SECONDS * 1000L;
        boolean cnCloseWindow = MarketHours.withinCloseWindow("CN", Instant.ofEpochMilli(now),
            CONFIG.marketHolidays().getOrDefault("CN", java.util.Set.of()), 5);
        boolean idle = isQuoteIdleEligible(noPlayers, inactiveForHour, cnCloseWindow);
        String reason = cnCloseWindow ? "CN close window"
            : noPlayers ? "no players online"
            : inactiveForHour ? "no market command for one hour" : "market active";
        QUOTE_REFRESHER.setIdleMode(idle, reason);
    }

    static boolean isInactiveForIdle(long uptimeSeconds, long inactiveSeconds) {
        return uptimeSeconds >= IDLE_STARTUP_GRACE_SECONDS
            && inactiveSeconds >= IDLE_INACTIVITY_THRESHOLD_SECONDS;
    }

    static boolean isQuoteIdleEligible(boolean noPlayers, boolean inactiveForHour, boolean cnCloseWindow) {
        return !cnCloseWindow && (noPlayers || inactiveForHour);
    }

    public static CompletableFuture<Void> prepareMarketCommand(net.minecraft.commands.CommandSourceStack source) {
        if (!CONFIG.setupInitialized())
            return CompletableFuture.failedFuture(new IllegalStateException("market data is not initialized"));
        lastMarketActivityAt = System.currentTimeMillis();
        if (QUOTE_REFRESHER == null)
            return CompletableFuture.failedFuture(new IllegalStateException("quote service unavailable"));
        return QUOTE_REFRESHER.prepareForPlayerQuery("player market command")
            .thenApply(ignored -> null);
    }

    private static String activityStatus() {
        long now = System.currentTimeMillis();
        long uptimeSeconds = serverStartedAt == 0 ? 0 : Math.max(0, (now - serverStartedAt) / 1000L);
        long inactiveSeconds = lastMarketActivityAt == 0 ? 0 : Math.max(0, (now - lastMarketActivityAt) / 1000L);
        boolean noPlayers = SERVER != null && SERVER.getPlayerList().getPlayers().isEmpty();
        boolean inactiveForHour = isInactiveForIdle(uptimeSeconds, inactiveSeconds);
        long secondsUntilIdle = noPlayers ? 0 : Math.max(0, IDLE_INACTIVITY_THRESHOLD_SECONDS - inactiveSeconds);
        boolean closeWindow = MarketHours.withinCloseWindow("CN", Instant.ofEpochMilli(now),
            CONFIG.marketHolidays().getOrDefault("CN", java.util.Set.of()), 5);
        String mode = QUOTE_REFRESHER != null && QUOTE_REFRESHER.isIdleMode() ? "idle" : "active";
        return "{\"mode\":" + jsonString(mode)
            + ",\"serverStartedAt\":" + jsonTime(serverStartedAt)
            + ",\"lastMarketActivityAt\":" + jsonTime(lastMarketActivityAt)
            + ",\"onlinePlayers\":" + (SERVER == null ? 0 : SERVER.getPlayerList().getPlayerCount())
            + ",\"uptimeSeconds\":" + uptimeSeconds
            + ",\"inactiveSeconds\":" + inactiveSeconds
            + ",\"idleThresholdSeconds\":3600"
            + ",\"noPlayers\":" + noPlayers
            + ",\"inactiveForHour\":" + inactiveForHour
            + ",\"secondsUntilIdle\":" + secondsUntilIdle
            + ",\"outsideCnCloseWindow\":" + !closeWindow
            + ",\"idleEligible\":" + isQuoteIdleEligible(noPlayers, inactiveForHour, closeWindow)
            + ",\"cnCloseWindow\":" + closeWindow + "}";
    }

    public static CompletableFuture<String> inspectMarketData(String path) {
        if (QUOTE_REFRESHER == null)
            return CompletableFuture.failedFuture(new IllegalStateException("quote service unavailable"));
        try {
            JsonObject status = JsonParser.parseString(QUOTE_REFRESHER.providerStatus()).getAsJsonObject();
            status.add("scheduler", JsonParser.parseString(QUOTE_REFRESHER.schedulerStatus()));
            status.add("announcements", JsonParser.parseString(announcementStatus()));
            status.add("activity", JsonParser.parseString(activityStatus()));
            return CompletableFuture.completedFuture(status.toString());
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static String announcementStatus() {
        StringBuilder markets = new StringBuilder("{");
        boolean first = true;
        for (String market : new String[]{"CN", "CRYPTO"}) {
            if (!first) markets.append(',');
            first = false;
            MarketStatus status = MARKET_STATUSES.get(market);
            markets.append(jsonString(market)).append(":{\"status\":")
                .append(jsonString(status == null ? "UNKNOWN" : status.name()))
                .append(",\"lastEventAt\":")
                .append(jsonTime(MARKET_EVENT_AT.getOrDefault(market, 0L))).append('}');
        }
        markets.append('}');
        return "{\"startupAnnouncementSentAt\":" + jsonTime(startupAnnouncementSentAt)
            + ",\"lastBriefingSentAt\":" + jsonTime(lastBriefingSentAt)
            + ",\"nextBriefingAt\":" + jsonTime(nextBriefingAt)
            + ",\"lastBriefingSnapshotId\":" + jsonString(lastBriefingSnapshotId)
            + ",\"markets\":" + markets + "}";
    }

    private static String jsonTime(long epochMillis) {
        return epochMillis == 0 || epochMillis == Long.MAX_VALUE
            ? "null" : jsonString(Instant.ofEpochMilli(epochMillis).toString());
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static CompletableFuture<String> controlMarketData(String path) {
        try {
            if (QUOTE_REFRESHER == null)
                throw new IllegalStateException("quote service unavailable");
            String query = path.substring(path.indexOf("?") + 1);
            Map<String, String> values = new HashMap<>();
            for (String item : query.split("&")) {
                String[] pair = item.split("=", 2);
                if (pair.length == 2)
                    values.put(pair[0], pair[1]);
            }
            String market = values.get("market");
            boolean enabled = Boolean.parseBoolean(values.get("enabled"));
            if (path.startsWith("/control/market")) {
                QUOTE_REFRESHER.setMarketEnabled(market, enabled);
                FinanceConfig.writeMarketEnabled(CONFIG_PATH, market, enabled);
            } else if (path.startsWith("/control/provider")) {
                String provider = values.get("provider");
                QUOTE_REFRESHER.setProviderEnabled(market, provider, enabled);
                FinanceConfig.writeProviderEnabled(CONFIG_PATH, market, provider, enabled);
            }
            else
                throw new IllegalArgumentException("unknown control path");
            return CompletableFuture.completedFuture("{}");
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static void startQuoteRefresh() {
        startQuoteRefresh(true);
    }

    private static void startQuoteRefresh(boolean immediate) {
        if (QUOTE_STORE == null || QUOTE_REFRESHER != null)
            return;

        QUOTE_REFRESHER = new QuoteRefreshService(
            QUOTE_STORE, CONFIG, ImyvmFinance::notifyQuoteAlert, ImyvmFinance::handleQuoteSnapshot);
        if (immediate)
            QUOTE_REFRESHER.start();
        else
            QUOTE_REFRESHER.startAfterInitialSnapshot();
    }

    private static boolean isBriefingSnapshot(com.imyvm.finance.market.QuoteSnapshot snapshot) {
        long age = System.currentTimeMillis() - snapshot.fetchedAtEpochMillis();
        if (age < 0 || age >= CONFIG.quotePollIntervalMinutes() * 60_000L)
            return false;
        return isBriefingPollNode(Instant.ofEpochMilli(snapshot.fetchedAtEpochMillis()), MarketHours.CHINA_ZONE,
            CONFIG.quotePollDelaySeconds(), CONFIG.quoteJitterSeconds(), CONFIG.briefingIntervalMinutes());
    }

    static boolean isBriefingPollNode(Instant instant, ZoneId zone, long pollDelaySeconds, long jitterSeconds, long briefingIntervalMinutes) {
        ZonedDateTime time = instant.atZone(zone);
        long seconds = time.getMinute() * 60L + time.getSecond();
        long anchor = 60L + pollDelaySeconds;
        long interval = briefingIntervalMinutes * 60L;
        long distance = Math.floorMod(seconds - anchor, interval);
        long tolerance = Math.max(1L, jitterSeconds);
        return Math.min(distance, interval - distance) <= tolerance;
    }

    private static Component playerMessage(Component message) {
        return Component.empty().append(Translator.tr("commands.market.disclaimer"))
            .append("\n").append(message);
    }

    private static void sendStartupAnnouncement(net.minecraft.server.MinecraftServer server) {
        startupAnnouncementSentAt = System.currentTimeMillis();
        Component message = Translator.tr("commands.market.notice.startup");
        for (var player : server.getPlayerList().getPlayers())
            player.sendSystemMessage(playerMessage(message));
    }

    private static void handleQuoteSnapshot(com.imyvm.finance.market.QuoteSnapshot snapshot) {
        net.minecraft.server.MinecraftServer server = SERVER;
        if (server == null)
            return;
        server.execute(() -> {
            if (!CONFIG.setupInitialized())
                return;
            if (isBriefingSnapshot(snapshot) && !snapshot.snapshotId().equals(lastBriefingSnapshotId)) {
                lastBriefingSnapshotId = snapshot.snapshotId();
                nextBriefingAt = snapshot.fetchedAtEpochMillis() + CONFIG.briefingDelaySeconds() * 1000L;
            }
            updateMarketAnnouncements(server, snapshot);
        });
    }

    private static void updateMarketAnnouncements(
        net.minecraft.server.MinecraftServer server,
        com.imyvm.finance.market.QuoteSnapshot snapshot
    ) {
        Map<String, MarketStatus> current = new HashMap<>();
        for (Instrument instrument : Instrument.values()) {
            MarketStatus status = current.get(instrument.market());
            MarketStatus quoteStatus = snapshot.quotes().stream()
                .filter(quote -> quote.instrument() == instrument)
                .map(com.imyvm.finance.market.MarketQuote::status)
                .findFirst()
                .orElse(MarketStatus.UNAVAILABLE);
            if (quoteStatus == MarketStatus.OPEN || status == null)
                current.put(instrument.market(), quoteStatus);
        }
        for (Map.Entry<String, MarketStatus> entry : current.entrySet()) {
            MarketStatus previous = MARKET_STATUSES.put(entry.getKey(), entry.getValue());
            if (entry.getValue() == MarketStatus.OPEN
                && (previous == null || previous == MarketStatus.CLOSED))
                sendMarketEvent(server, "commands.market.notice.opened", entry.getKey());
            else if (entry.getValue() == MarketStatus.CLOSED && previous == MarketStatus.OPEN)
                sendMarketEvent(server, "commands.market.notice.closed", entry.getKey());
        }
    }

    private static Component marketLabel(String market) {
        return Translator.tr("commands.market.market.name." + market.toLowerCase());
    }

    private static void sendMarketEvent(net.minecraft.server.MinecraftServer server,
                                        String key, String market) {
        Component message = Translator.tr(key, marketLabel(market));
        MARKET_EVENT_AT.put(market, System.currentTimeMillis());
        for (var player : server.getPlayerList().getPlayers())
            player.sendSystemMessage(playerMessage(message));
    }

    public static CompletableFuture<QuoteSnapshot> checkMarketData() {
        if (CONFIG.setupInitialized())
            return CompletableFuture.failedFuture(new IllegalStateException("market data already initialized"));
        if (!SETUP_CHECKING.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("market data setup check already in progress"));
        return new DirectMarketQuoteClient(CONFIG.quoteConnectTimeout(), CONFIG.quoteReadTimeout(), CONFIG.marketHolidays(), CONFIG.marketEnabled(), CONFIG.disabledProviders(), CONFIG.providerOrder())
            .fetch()
            .whenComplete((snapshot, error) -> {
                if (error != null)
                    SETUP_CHECKING.set(false);
            });
    }

    public static boolean setupCheckInProgress() {
        return SETUP_CHECKING.get();
    }

    public static void configureQuoteSettings(long interval, long idleInterval, long delay, long jitter, long seed, long briefingInterval, long briefingDelay, boolean briefingEnabled) throws java.io.IOException {
        FinanceConfig.writeQuoteSettings(CONFIG_PATH, interval, idleInterval, delay, jitter, seed, briefingInterval, briefingDelay, briefingEnabled);
    }

    public static void configureProviderOrder(String market, String providers) throws java.io.IOException {
        FinanceConfig.writeProviderOrder(CONFIG_PATH, market, providers);
    }

    public static void configureHolidays(String market, String dates) throws java.io.IOException {
        FinanceConfig.writeHolidays(CONFIG_PATH, market, dates);
    }

    public static void completeSetup(QuoteSnapshot initialSnapshot) throws Exception {
        try {
            if (CONFIG_PATH == null)
                throw new IllegalStateException("finance config path is unavailable");
            if (QUOTE_STORE == null)
                throw new IllegalStateException("quote storage is unavailable");
            QUOTE_STORE.save(initialSnapshot);
            FinanceConfig.writeSetupInitialized(CONFIG_PATH, true);
            CONFIG = CONFIG.withSetupInitialized(true);
            handleQuoteSnapshot(initialSnapshot);
            startQuoteRefresh(false);
        } finally {
            SETUP_CHECKING.set(false);
        }
    }

    private static void notifyQuoteAlert(String alert) {
        if (!CONFIG.setupInitialized())
            return;
        long alertId = -1L;
        if (TRADING_STORE != null) {
            try {
                alertId = TRADING_STORE.enqueueMarketAlert(alert, System.currentTimeMillis());
            } catch (Exception exception) {
                LOGGER.error("Failed to persist market alert {}", alert, exception);
            }
        }
        net.minecraft.server.MinecraftServer server = SERVER;
        if (server == null)
            return;
        long persistedAlertId = alertId;
        server.execute(() -> {
            Component message = marketAlertMessage(alert);
            for (var player : server.getPlayerList().getPlayers()) {
                if (player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
                    player.sendSystemMessage(playerMessage(message));
                    if (persistedAlertId >= 0 && TRADING_STORE != null) {
                        try {
                            TRADING_STORE.markMarketAlertDelivered(player.getUUID(), persistedAlertId);
                        } catch (Exception exception) {
                            LOGGER.error("Failed to mark market alert delivered for {}", player.getUUID(), exception);
                        }
                    }
                }
            }
        });
    }

    private static Component marketAlertMessage(String alert) {
        boolean recovered = alert.startsWith("recovered:");
        String value = alert.substring((recovered ? "recovered:" : "failed:").length());
        if (value.startsWith("market:"))
            return Translator.tr("commands.market.quote." + (recovered ? "market_recovered" : "market_failed"),
                value.substring("market:".length()));
        return Translator.tr("commands.market.quote." + (recovered ? "recovered" : "failed"), value);
    }

    private static void closeQuoteStore() {
        SERVER = null;
        if (QUOTE_REFRESHER != null) {
            QUOTE_REFRESHER.close();
            QUOTE_REFRESHER = null;
        }
        ECONOMY_SETTLEMENT = null;
        if (TRADING_STORE != null) {
            try {
                TRADING_STORE.close();
            } catch (Exception exception) {
                LOGGER.error("Failed to close finance trading storage", exception);
            } finally {
                TRADING_STORE = null;
            }
        }
        if (TRANSACTION_STORE != null) {
            try {
                TRANSACTION_STORE.close();
            } catch (Exception exception) {
                LOGGER.error("Failed to close finance transaction storage", exception);
            } finally {
                TRANSACTION_STORE = null;
            }
        }
        if (QUOTE_STORE == null)
            return;

        try {
            QUOTE_STORE.close();
        } catch (Exception exception) {
            LOGGER.error("Failed to close finance quote storage", exception);
        } finally {
            QUOTE_STORE = null;
        }
    }
}
