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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;

public record FinanceConfig(
    Duration quoteConnectTimeout,
    Duration quoteReadTimeout,
    long quotePollIntervalMinutes,
    long quotePollDelaySeconds,
    long quoteJitterSeconds,
    long quoteRandomSeed,
    long briefingIntervalMinutes,
    long briefingDelaySeconds,
    boolean briefingEnabled,
    boolean setupInitialized,
    Map<String, Set<LocalDate>> marketHolidays,
    Map<String, Boolean> marketEnabled,
    Set<String> disabledProviders,
    Map<String, java.util.List<String>> providerOrder,
    String language,
    TradingRules tradingRules
) {

    public static FinanceConfig defaults() {
        return new FinanceConfig(
                Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            5,
            17,
            15,
            0,
            20,
            20,
            true,
            false,
            Map.of(),
            Map.of("CN", true, "HK", true, "US", true, "CRYPTO", true),
            Set.of(),
            Map.of("CN", java.util.List.of("eastmoney", "sina"), "HK", java.util.List.of("yahoo"), "US", java.util.List.of("yahoo"), "CRYPTO", java.util.List.of("binance", "coinbase", "kraken", "okx")),
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
                properties.setProperty("quote.jitter-seconds",
                    Long.toString(defaults.quoteJitterSeconds()));
                properties.setProperty("quote.random-seed",
                    Long.toString(defaults.quoteRandomSeed()));
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
            nonNegativeLong(properties, "quote.jitter-seconds", defaults.quoteJitterSeconds()),
            parseLong(properties, "quote.random-seed", defaults.quoteRandomSeed()),
            positiveLong(properties, "briefing.interval-minutes", defaults.briefingIntervalMinutes()),
            positiveLong(properties, "briefing.delay-seconds", defaults.briefingDelaySeconds()),
            parseBoolean(properties, "briefing.enabled", defaults.briefingEnabled()),
            parseBoolean(properties, "setup.initialized", defaults.setupInitialized()),
            parseHolidays(properties),
            parseMarketEnabled(properties),
            parseDisabledProviders(properties),
            parseProviderOrder(properties),
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
            quotePollIntervalMinutes, quotePollDelaySeconds, quoteJitterSeconds, quoteRandomSeed, briefingIntervalMinutes, briefingDelaySeconds,
            briefingEnabled, initialized, marketHolidays, marketEnabled, disabledProviders, providerOrder, language, tradingRules);
    }

    public static void writeMarketEnabled(Path path, String market, boolean enabled) throws IOException {
        Properties properties = readProperties(path);
        properties.setProperty("market.enabled." + market, Boolean.toString(enabled));
        writeProperties(path, properties);
    }

    public static void writeProviderEnabled(Path path, String market, String provider, boolean enabled) throws IOException {
        Properties properties = readProperties(path);
        Set<String> disabled = parseDisabledProviders(properties);
        String key = market + ":" + provider;
        if (enabled) disabled.remove(key); else disabled.add(key);
        properties.setProperty("market.disabled-providers", String.join(",", disabled));
        writeProperties(path, properties);
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
        return properties;
    }

    private static void writeProperties(Path path, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) { properties.store(writer, "ImyvmFinance settings"); }
    }

    private static Map<String, java.util.List<String>> parseProviderOrder(Properties properties) {
        Map<String, java.util.List<String>> defaults = Map.of(
            "CN", java.util.List.of("eastmoney", "sina"),
            "HK", java.util.List.of("yahoo"),
            "US", java.util.List.of("yahoo"),
            "CRYPTO", java.util.List.of("binance", "coinbase", "kraken", "okx"));
        Map<String, java.util.List<String>> result = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> entry : defaults.entrySet()) {
            java.util.List<String> providers = new ArrayList<>();
            for (String value : properties.getProperty("market.providers." + entry.getKey(), String.join(",", entry.getValue())).split(","))
                if (!value.trim().isEmpty()) providers.add(value.trim().toLowerCase());
            result.put(entry.getKey(), java.util.List.copyOf(providers));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Boolean> parseMarketEnabled(Properties properties) {
        Map<String, Boolean> result = new HashMap<>();
        for (String market : new String[] {"CN", "HK", "US", "CRYPTO"})
            result.put(market, parseBoolean(properties, "market.enabled." + market, true));
        return Map.copyOf(result);
    }

    private static Set<String> parseDisabledProviders(Properties properties) {
        Set<String> result = new HashSet<>();
        for (String value : properties.getProperty("market.disabled-providers", "").split(","))
            if (!value.trim().isEmpty()) result.add(value.trim());
        return Set.copyOf(result);
    }

    public static void writeQuoteSettings(Path path, long interval, long delay, long jitter, long seed, long briefingInterval, long briefingDelay, boolean briefingEnabled) throws IOException {
        Properties properties = readProperties(path);
        properties.setProperty("quote.poll-interval-minutes", Long.toString(interval));
        properties.setProperty("quote.poll-delay-seconds", Long.toString(delay));
        properties.setProperty("quote.jitter-seconds", Long.toString(jitter));
        properties.setProperty("quote.random-seed", Long.toString(seed));
        properties.setProperty("briefing.interval-minutes", Long.toString(briefingInterval));
        properties.setProperty("briefing.delay-seconds", Long.toString(briefingDelay));
        properties.setProperty("briefing.enabled", Boolean.toString(briefingEnabled));
        writeProperties(path, properties);
    }

    public static void writeProviderOrder(Path path, String market, String providers) throws IOException {
        Properties properties = readProperties(path);
        properties.setProperty("market.providers." + market, providers);
        writeProperties(path, properties);
    }

    public static void writeHolidays(Path path, String market, String dates) throws IOException {
        Properties properties = readProperties(path);
        properties.setProperty("market.holidays." + market, dates);
        writeProperties(path, properties);
    }

    public static void writeSetupInitialized(Path path, boolean initialized) throws IOException {
        Properties properties = readProperties(path);
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
