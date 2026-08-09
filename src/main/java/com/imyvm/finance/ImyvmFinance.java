package com.imyvm.finance;

import com.imyvm.finance.market.MarketCommands;
import com.imyvm.finance.storage.QuoteSnapshotStore;
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

    @Override
    public void onInitialize() {
        try {
            Path databasePath = FabricLoader.getInstance().getGameDir()
                .resolve(MOD_ID)
                .resolve("finance.db");
            QUOTE_STORE = QuoteSnapshotStore.open(databasePath);
        } catch (Exception exception) {
            LOGGER.error("Finance quote storage is unavailable", exception);
        }

        CommandRegistrationCallback.EVENT.register(MarketCommands::register);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> closeQuoteStore());
        LOGGER.info("Initializing Imyvm Finance");
    }

    private static void closeQuoteStore() {
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
