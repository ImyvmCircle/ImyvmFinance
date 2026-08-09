package com.imyvm.finance;

import com.imyvm.finance.market.MarketCommands;
import com.imyvm.finance.storage.QuoteSnapshotStore;
import com.imyvm.finance.storage.StockTransactionStore;
import com.imyvm.finance.storage.StockTradingStore;
import com.imyvm.finance.economy.StockEconomySettlement;
import com.imyvm.finance.quote.QuoteRefreshService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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
        ServerLifecycleEvents.SERVER_STARTED.register(server -> startQuoteRefresh());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> closeQuoteStore());
        LOGGER.info("Initializing Imyvm Finance");
    }

    private static void startQuoteRefresh() {
        if (QUOTE_STORE == null || QUOTE_REFRESHER != null)
            return;

        QUOTE_REFRESHER = new QuoteRefreshService(QUOTE_STORE, CONFIG);
        QUOTE_REFRESHER.start();
    }

    private static void closeQuoteStore() {
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
