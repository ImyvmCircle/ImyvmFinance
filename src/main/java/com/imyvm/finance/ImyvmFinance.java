package com.imyvm.finance;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImyvmFinance implements ModInitializer {
    public static final String MOD_ID = "imyvm_finance";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Imyvm Finance");
    }
}
