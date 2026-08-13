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

public final class ImyvmFinance implements ModInitializer {
    public static final String MOD_ID = "imyvm_finance";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static QuoteSnapshotStore QUOTE_STORE;
    public static FinanceConfig CONFIG = FinanceConfig.defaults();
    public static com.imyvm.finance.trading.TradingRules TRADING_RULES = CONFIG.tradingRules();
    public static StockTransactionStore TRANSACTION_STORE;
    public static StockTradingStore TRADING_STORE;
    public static StockEconomySettlement ECONOMY_SETTLEMENT;
    public static QuoteRefreshService QUOTE_REFRESHER;
    private static final long RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static long nextRetentionCleanupAt;
    private static long nextBriefingAt;
    private static volatile net.minecraft.server.MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        try {
            Path configPath = FabricLoader.getInstance().getConfigDir()
                .resolve(MOD_ID + ".properties");
            CONFIG = FinanceConfig.load(configPath);
            TRADING_RULES = CONFIG.tradingRules();
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
            recoverInterruptedTransactions();
            pruneExpiredData();
            nextBriefingAt = System.currentTimeMillis() + Duration.ofMinutes(CONFIG.briefingIntervalMinutes()).toMillis();
            startQuoteRefresh();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            pruneExpiredData();
            sendMarketBriefing(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            notifyPendingSettlement(handler.getPlayer()));
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
        if (TRANSACTION_STORE == null)
            return;
        try {
            int count = TRANSACTION_STORE.pendingSettlementCount(player.getUUID());
            if (count > 0)
                player.sendSystemMessage(Translator.tr("commands.market.pending.player_notice", count));
        } catch (Exception exception) {
            LOGGER.error("Failed to read pending finance settlements for {}", player.getUUID(), exception);
        }
    }

    private static void sendMarketBriefing(net.minecraft.server.MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now < nextBriefingAt || QUOTE_STORE == null || TRADING_STORE == null)
            return;
        nextBriefingAt = now + Duration.ofMinutes(CONFIG.briefingIntervalMinutes()).toMillis();
        MutableComponent briefing = Component.empty().append(Translator.tr("commands.market.briefing.header"));
        for (String market : new String[]{"CN", "HK", "US", "JP", "KR"}) {
            MutableComponent line = Component.empty().append("\n").append(Translator.tr("commands.market.briefing.market", market));
            for (Instrument instrument : Instrument.values()) {
                if (!instrument.market().equals(market))
                    continue;
                try {
                    java.util.Optional<StoredQuote> stored = QUOTE_STORE.findLatest(instrument);
                    if (stored.isEmpty())
                        continue;
                    StoredQuote quote = stored.get();
                    boolean tradable = quote.quote().status() == MarketStatus.OPEN
                        && TRADING_STORE.isGlobalTradingEnabled() && TRADING_STORE.isTradingEnabled(instrument);
                    Component name = Translator.tr("commands.market.briefing.instrument", instrument.symbol()).copy();
                    if (tradable)
                        name = name.copy().withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand(
                                "/market estimate " + instrument.symbol() + " " + TRADING_RULES.minUnits()))
                            .withHoverEvent(new HoverEvent.ShowText(Translator.tr("commands.market.briefing.buy_hint")))
                            .withUnderlined(true));
                    line.append(Translator.tr("commands.market.briefing.item", name,
                        formatPrice(quote.quote().priceScaled()), formatPercent(quote.quote().changeBps()),
                        briefingStatus(quote, tradable)));
                } catch (Exception exception) {
                    LOGGER.warn("Failed to prepare market briefing for {}", instrument.symbol(), exception);
                }
            }
            briefing.append(line);
        }
        for (var player : server.getPlayerList().getPlayers())
            player.sendSystemMessage(briefing);
    }

    private static Component briefingStatus(StoredQuote quote, boolean tradable) {
        if (tradable)
            return Translator.tr("commands.market.briefing.status.open");
        if (quote.quote().status() == MarketStatus.OPEN)
            return Translator.tr("commands.market.briefing.status.paused");
        return Translator.tr("commands.market.briefing.status." + quote.quote().status().name().toLowerCase());
    }

    private static String formatPrice(long priceScaled) {
        return java.math.BigDecimal.valueOf(priceScaled, 4).stripTrailingZeros().toPlainString();
    }

    private static String formatPercent(long changeBps) {
        return java.math.BigDecimal.valueOf(changeBps, 2).setScale(2).toPlainString() + "%";
    }

    private static void startQuoteRefresh() {
        if (QUOTE_STORE == null || QUOTE_REFRESHER != null)
            return;

        QUOTE_REFRESHER = new QuoteRefreshService(QUOTE_STORE, CONFIG, ImyvmFinance::notifyQuoteAlert);
        QUOTE_REFRESHER.start();
    }

    private static void notifyQuoteAlert(String alert) {
        net.minecraft.server.MinecraftServer server = SERVER;
        if (server == null)
            return;
        server.execute(() -> {
            Component message = alert.startsWith("recovered:")
                ? Translator.tr("commands.market.quote.recovered", alert.substring("recovered:".length()))
                : Translator.tr("commands.market.quote.failed", alert.substring("failed:".length()));
            for (var player : server.getPlayerList().getPlayers()) {
                if (player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                    player.sendSystemMessage(message);
            }
        });
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
