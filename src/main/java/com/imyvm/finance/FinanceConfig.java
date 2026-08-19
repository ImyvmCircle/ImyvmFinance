package com.imyvm.finance;

import com.imyvm.finance.trading.TradingRules;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record FinanceConfig(
    URI sidecarEndpoint,
    Duration sidecarConnectTimeout,
    Duration sidecarReadTimeout,
    long quotePollIntervalMinutes,
    long quotePollDelaySeconds,
    long briefingIntervalMinutes,
    long briefingDelaySeconds,
    boolean briefingEnabled,
    boolean setupInitialized,
    String language,
    TradingRules tradingRules
) {
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765/quotes";

    public static FinanceConfig defaults() {
        return new FinanceConfig(
            URI.create(DEFAULT_ENDPOINT),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            5,
            17,
            20,
            20,
            true,
            false,
            "zh_cn",
            TradingRules.DEFAULT);
    }

    public static FinanceConfig load(Path path) throws IOException {
        FinanceConfig defaults = defaults();
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
        } else {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.setProperty("sidecar.endpoint", defaults.sidecarEndpoint().toString());
                properties.setProperty("sidecar.connect-timeout-ms",
                    Long.toString(defaults.sidecarConnectTimeout().toMillis()));
                properties.setProperty("sidecar.read-timeout-ms",
                    Long.toString(defaults.sidecarReadTimeout().toMillis()));
                properties.setProperty("sidecar.poll-interval-minutes",
                    Long.toString(defaults.quotePollIntervalMinutes()));
                properties.setProperty("sidecar.poll-delay-seconds",
                    Long.toString(defaults.quotePollDelaySeconds()));
                properties.setProperty("briefing.interval-minutes",
                    Long.toString(defaults.briefingIntervalMinutes()));
                properties.setProperty("briefing.delay-seconds",
                    Long.toString(defaults.briefingDelaySeconds()));
                properties.setProperty("briefing.enabled", Boolean.toString(defaults.briefingEnabled()));
                properties.setProperty("setup.initialized", Boolean.toString(defaults.setupInitialized()));
                properties.setProperty("language", defaults.language());
                properties.setProperty("trading.max-quote-age-minutes", "15");
                properties.setProperty("trading.sell-cooldown-minutes", "30");
                properties.setProperty("trading.fee-bps", "20");
                properties.setProperty("trading.base-slippage-bps", "10");
                properties.setProperty("trading.daily-buy-limit", "100000");
                properties.setProperty("trading.daily-sell-limit", "100000");
                properties.setProperty("trading.position-value-limit", "300000");
                properties.setProperty("trading.min-units", "1");
                properties.store(writer, "ImyvmFinance settings");
            }
        }

        return new FinanceConfig(
            URI.create(properties.getProperty("sidecar.endpoint", defaults.sidecarEndpoint().toString())),
            positiveDuration(properties, "sidecar.connect-timeout-ms",
                defaults.sidecarConnectTimeout().toMillis()),
            positiveDuration(properties, "sidecar.read-timeout-ms",
                defaults.sidecarReadTimeout().toMillis()),
            positiveLong(properties, "sidecar.poll-interval-minutes", defaults.quotePollIntervalMinutes()),
            positiveLong(properties, "sidecar.poll-delay-seconds", defaults.quotePollDelaySeconds()),
            positiveLong(properties, "briefing.interval-minutes", defaults.briefingIntervalMinutes()),
            positiveLong(properties, "briefing.delay-seconds", defaults.briefingDelaySeconds()),
            parseBoolean(properties, "briefing.enabled", defaults.briefingEnabled()),
            parseBoolean(properties, "setup.initialized", defaults.setupInitialized()),
            properties.getProperty("language", defaults.language()).trim(),
            new TradingRules(
                positiveLong(properties, "trading.max-quote-age-minutes", 15) * 60 * 1000,
                positiveLong(properties, "trading.sell-cooldown-minutes", 30) * 60 * 1000,
                nonNegativeInt(properties, "trading.fee-bps", 20),
                nonNegativeInt(properties, "trading.base-slippage-bps", 10),
                nonNegativeLong(properties, "trading.daily-buy-limit", 100000),
                nonNegativeLong(properties, "trading.daily-sell-limit", 100000),
                nonNegativeLong(properties, "trading.position-value-limit", 300000),
                positiveLong(properties, "trading.min-units", 1)));
    }

    public FinanceConfig withSetupInitialized(boolean initialized) {
        return new FinanceConfig(sidecarEndpoint, sidecarConnectTimeout, sidecarReadTimeout,
            quotePollIntervalMinutes, quotePollDelaySeconds, briefingIntervalMinutes, briefingDelaySeconds,
            briefingEnabled, initialized, language, tradingRules);
    }

    public static void writeSetupInitialized(Path path, boolean initialized) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
        }
        properties.setProperty("setup.initialized", Boolean.toString(initialized));
        try (Writer writer = Files.newBufferedWriter(path)) {
            properties.store(writer, "ImyvmFinance settings");
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static Duration positiveDuration(Properties properties, String key, long fallback) {
        return Duration.ofMillis(positiveLong(properties, key, fallback));
    }

    private static long positiveLong(Properties properties, String key, long fallback) {
        long value = parseLong(properties, key, fallback);
        return value > 0 ? value : fallback;
    }

    private static long nonNegativeLong(Properties properties, String key, long fallback) {
        long value = parseLong(properties, key, fallback);
        return value >= 0 ? value : fallback;
    }

    private static int nonNegativeInt(Properties properties, String key, int fallback) {
        long value = nonNegativeLong(properties, key, fallback);
        return value <= Integer.MAX_VALUE ? (int) value : fallback;
    }

    private static long parseLong(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallback)).trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
