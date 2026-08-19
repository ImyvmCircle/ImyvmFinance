package com.imyvm.finance;

import com.imyvm.finance.trading.TradingRules;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

public record FinanceConfig(
    Duration quoteConnectTimeout,
    Duration quoteReadTimeout,
    long quotePollIntervalMinutes,
    long quotePollDelaySeconds,
    long briefingIntervalMinutes,
    long briefingDelaySeconds,
    boolean briefingEnabled,
    boolean setupInitialized,
    Map<String, Set<LocalDate>> marketHolidays,
    String language,
    TradingRules tradingRules
) {

    public static FinanceConfig defaults() {
        return new FinanceConfig(
                Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            5,
            17,
            20,
            20,
            true,
            false,
            Map.of(),
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
                properties.setProperty("market.connect-timeout-ms",
                    Long.toString(defaults.quoteConnectTimeout().toMillis()));
                properties.setProperty("market.read-timeout-ms",
                    Long.toString(defaults.quoteReadTimeout().toMillis()));
                properties.setProperty("quote.poll-interval-minutes",
                    Long.toString(defaults.quotePollIntervalMinutes()));
                properties.setProperty("quote.poll-delay-seconds",
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
            positiveDuration(properties, "market.connect-timeout-ms",
                defaults.quoteConnectTimeout().toMillis()),
            positiveDuration(properties, "market.read-timeout-ms",
                defaults.quoteReadTimeout().toMillis()),
            positiveLong(properties, "quote.poll-interval-minutes", defaults.quotePollIntervalMinutes()),
            positiveLong(properties, "quote.poll-delay-seconds", defaults.quotePollDelaySeconds()),
            positiveLong(properties, "briefing.interval-minutes", defaults.briefingIntervalMinutes()),
            positiveLong(properties, "briefing.delay-seconds", defaults.briefingDelaySeconds()),
            parseBoolean(properties, "briefing.enabled", defaults.briefingEnabled()),
            parseBoolean(properties, "setup.initialized", defaults.setupInitialized()),
            parseHolidays(properties),
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
        return new FinanceConfig(quoteConnectTimeout, quoteReadTimeout,
            quotePollIntervalMinutes, quotePollDelaySeconds, briefingIntervalMinutes, briefingDelaySeconds,
            briefingEnabled, initialized, marketHolidays, language, tradingRules);
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

    private static Map<String, Set<LocalDate>> parseHolidays(Properties properties) {
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        for (String market : new String[] {"CN", "HK", "US"}) {
            Set<LocalDate> dates = new HashSet<>();
            for (String value : properties.getProperty("market.holidays." + market, "").split(",")) {
                try {
                    if (!value.trim().isEmpty())
                        dates.add(LocalDate.parse(value.trim()));
                } catch (RuntimeException ignored) {
                }
            }
            if (!dates.isEmpty())
                holidays.put(market, Set.copyOf(dates));
        }
        return Map.copyOf(holidays);
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
