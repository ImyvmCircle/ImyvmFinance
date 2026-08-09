package com.imyvm.finance;

import com.imyvm.finance.market.MarketCommands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImyvmFinance implements ModInitializer {
    public static final String MOD_ID = "imyvm_finance";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(MarketCommands::register);
        LOGGER.info("Initializing Imyvm Finance");
    }
}
