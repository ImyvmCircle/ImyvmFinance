package com.imyvm.finance.quote;

import com.imyvm.finance.market.Instrument;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

public final class SimulationModelConfig {
    public static final String RESOURCE = "/imyvm_finance/simulation-models.properties";
    public static final Map<String, String> LAYER_DESCRIPTIONS;

    private static final Set<String> MODEL_FIELDS = Set.of(
        "volatility-multiplier", "trend-multiplier", "jump-multiplier",
        "common-factor-multiplier", "duration-multiplier", "downside-multiplier",
        "student-t-df-adjustment");
    private static final Set<String> STAT_FIELDS = Set.of(
        "annual-volatility", "downside-volatility-multiplier", "student-t-df",
        "garch-alpha", "garch-beta", "jump-rate-per-year", "jump-up-min",
        "jump-up-max", "jump-down-min", "jump-down-max", "jump-down-probability",
        "max-step-percent", "idiosyncratic-variance-floor", "trading-days-per-year",
        "trading-hours-per-day", "long-duration-min-days", "long-duration-max-days",
        "long-up-min", "long-up-max", "long-down-min", "long-down-max",
        "medium-duration-min-days", "medium-duration-max-days", "medium-up-min",
        "medium-up-max", "medium-down-min", "medium-down-max",
        "short-duration-min-days", "short-duration-max-days", "short-up-min",
        "short-up-max", "short-down-min", "short-down-max", "loading-cn-market",
        "loading-cn-style", "loading-crypto-market", "loading-risk-appetite",
        "loading-inflation-rate");
    private static final Set<String> FACTOR_FIELDS = Set.of(
        "random-bias", "up-probability", "up-amplitude-multiplier",
        "down-amplitude-multiplier");
    private static final Set<String> CORRELATION_FIELDS = Set.of(
        "minimum-scale", "maximum-scale", "cycle-effective-days");

    static {
        Map<String, String> layers = new LinkedHashMap<>();
        layers.put("LONG", "independent signed regime; sampled instrument duration and amplitude; factor biases direction probability only");
        layers.put("MEDIUM", "independent signed regime; sampled instrument duration and amplitude");
        layers.put("SHORT", "independent signed regime; sampled instrument duration and amplitude");
        LAYER_DESCRIPTIONS = Collections.unmodifiableMap(layers);
    }

    public record ModelSuite(
        String id,
        double volatilityMultiplier,
        double trendMultiplier,
        double jumpMultiplier,
        double commonFactorMultiplier,
        double durationMultiplier,
        double downsideMultiplier,
        int studentTDfAdjustment
    ) {
    }

    public record Regime(
        double durationMinDays,
        double durationMaxDays,
        double upMin,
        double upMax,
        double downMin,
        double downMax
    ) {
    }

    public record FactorLoadings(
        double cnMarket,
        double cnStyle,
        double cryptoMarket,
        double riskAppetite,
        double inflationRate
    ) {
        public double[] values() {
            return new double[] {cnMarket, cnStyle, cryptoMarket, riskAppetite, inflationRate};
        }
    }

    public record InstrumentStats(
        Instrument instrument,
        String profile,
        double annualVolatility,
        double downsideVolatilityMultiplier,
        int studentTDf,
        double garchAlpha,
        double garchBeta,
        double jumpRatePerYear,
        double jumpUpMin,
        double jumpUpMax,
        double jumpDownMin,
        double jumpDownMax,
        double jumpDownProbability,
        double maxStepPercent,
        double idiosyncraticVarianceFloor,
        double tradingDaysPerYear,
        double tradingHoursPerDay,
        Regime longRegime,
        Regime mediumRegime,
        Regime shortRegime,
        FactorLoadings loadings
    ) {
        public double effectiveMinutesPerYear() {
            return tradingDaysPerYear * tradingHoursPerDay * 60.0;
        }
    }

    public record FactorBias(
        boolean randomBias,
        double upProbability,
        double upAmplitudeMultiplier,
        double downAmplitudeMultiplier
    ) {
    }

    private final String defaultModelId;
    private final Map<String, ModelSuite> models;
    private final Map<Instrument, InstrumentStats> instruments;
    private final Map<Integer, FactorBias> factorBiases;
    private final double correlationMinimumScale;
    private final double correlationMaximumScale;
    private final double correlationCycleEffectiveDays;
    private final Map<String, String> properties;

    private SimulationModelConfig(
        String defaultModelId,
        Map<String, ModelSuite> models,
        Map<Instrument, InstrumentStats> instruments,
        Map<Integer, FactorBias> factorBiases,
        double correlationMinimumScale,
        double correlationMaximumScale,
        double correlationCycleEffectiveDays,
        Map<String, String> properties
    ) {
        this.defaultModelId = defaultModelId;
        this.models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
        this.instruments = Collections.unmodifiableMap(new LinkedHashMap<>(instruments));
        this.factorBiases = Collections.unmodifiableMap(new LinkedHashMap<>(factorBiases));
        this.correlationMinimumScale = correlationMinimumScale;
        this.correlationMaximumScale = correlationMaximumScale;
        this.correlationCycleEffectiveDays = correlationCycleEffectiveDays;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public static SimulationModelConfig bundledDefaults() {
        return load(new Properties());
    }

    public static SimulationModelConfig load(Properties overrides) {
        Properties merged = new Properties();
        try (InputStream input = SimulationModelConfig.class.getResourceAsStream(RESOURCE)) {
            if (input == null)
                throw new IllegalStateException("missing bundled simulation configuration: " + RESOURCE);
            merged.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read bundled simulation configuration: " + RESOURCE, exception);
        }
        for (String key : overrides.stringPropertyNames()) {
            if (key.startsWith("simulation.")
                && !key.startsWith("simulation.default-price.")
                && !isSimulationModelKey(key))
                throw new IllegalArgumentException("unknown simulation configuration key: " + key);
            if (isSimulationModelKey(key))
                merged.setProperty(key, overrides.getProperty(key));
        }
        return parse(merged);
    }

    public static SimulationModelConfig fromProperties(Properties properties) {
        return parse(properties);
    }

    public static SimulationModelConfig loadFile(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return parse(properties);
    }

    public static SimulationModelConfig fromSnapshot(String snapshot) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(snapshot));
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid frozen simulation configuration", exception);
        }
        return parse(properties);
    }

    public static boolean isSimulationModelKey(String key) {
        return key.equals("simulation.default-model")
            || key.equals("simulation.models")
            || key.equals("simulation.profiles")
            || key.startsWith("simulation.models.")
            || key.startsWith("simulation.profiles.")
            || key.startsWith("simulation.instruments.")
            || key.startsWith("simulation.factor.")
            || key.startsWith("simulation.correlation.");
    }

    public String defaultModelId() {
        return defaultModelId;
    }

    public List<String> modelIds() {
        return List.copyOf(models.keySet());
    }

    public ModelSuite model(String id) {
        ModelSuite model = models.get(id);
        if (model == null)
            throw new IllegalArgumentException("unknown simulation model: " + id);
        return model;
    }

    public InstrumentStats instrument(Instrument instrument) {
        InstrumentStats stats = instruments.get(instrument);
        if (stats == null)
            throw new IllegalArgumentException("missing simulation instrument: " + instrument.symbol());
        return stats;
    }

    public FactorBias factorBias(int factor) {
        FactorBias bias = factorBiases.get(factor);
        if (bias == null)
            throw new IllegalArgumentException("simulation factor must be between 0 and 5: " + factor);
        return bias;
    }

    public int resolvedStudentTDf(String modelId, Instrument instrument) {
        return Math.max(3, instrument(instrument).studentTDf() + model(modelId).studentTDfAdjustment());
    }

    public double correlationMinimumScale() {
        return correlationMinimumScale;
    }

    public double correlationMaximumScale() {
        return correlationMaximumScale;
    }

    public double correlationCycleEffectiveDays() {
        return correlationCycleEffectiveDays;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public String snapshot(String modelId) {
        model(modelId);
        TreeMap<String, String> selected = new TreeMap<>();
        selected.put("simulation.default-model", modelId);
        selected.put("simulation.models", modelId);
        String selectedPrefix = "simulation.models." + modelId + ".";
        for (var entry : properties.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(selectedPrefix)
                || key.equals("simulation.profiles")
                || key.startsWith("simulation.profiles.")
                || key.startsWith("simulation.instruments.")
                || key.startsWith("simulation.factor.")
                || key.startsWith("simulation.correlation."))
                selected.put(key, entry.getValue());
        }
        StringBuilder result = new StringBuilder();
        selected.forEach((key, value) -> result.append(key).append('=').append(value).append('\n'));
        return result.toString();
    }

    private static SimulationModelConfig parse(Properties source) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : source.stringPropertyNames())
            values.put(key, source.getProperty(key).trim());
        validateKeys(values);

        List<String> modelIds = csv(values, "simulation.models");
        Map<String, ModelSuite> models = new LinkedHashMap<>();
        for (String id : modelIds) {
            String prefix = "simulation.models." + id + ".";
            models.put(id, new ModelSuite(id,
                positive(values, prefix + "volatility-multiplier", 0.05, 5.0),
                positive(values, prefix + "trend-multiplier", 0.05, 5.0),
                positive(values, prefix + "jump-multiplier", 0.0, 10.0),
                positive(values, prefix + "common-factor-multiplier", 0.0, 2.0),
                positive(values, prefix + "duration-multiplier", 0.1, 5.0),
                positive(values, prefix + "downside-multiplier", 0.1, 3.0),
                integer(values, prefix + "student-t-df-adjustment", -10, 20)));
        }
        String defaultModel = required(values, "simulation.default-model");
        if (!models.containsKey(defaultModel))
            throw new IllegalArgumentException("simulation.default-model references unknown model: " + defaultModel);

        Set<String> profiles = new LinkedHashSet<>(csv(values, "simulation.profiles"));
        Map<Instrument, InstrumentStats> instruments = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.values()) {
            String instrumentPrefix = "simulation.instruments." + instrument.commandForm() + ".";
            String profile = required(values, instrumentPrefix + "profile");
            if (!profiles.contains(profile))
                throw new IllegalArgumentException(instrumentPrefix + "profile references unknown profile: " + profile);
            String profilePrefix = "simulation.profiles." + profile + ".";
            instruments.put(instrument, parseStats(values, instrument, profile, instrumentPrefix, profilePrefix));
        }

        Map<Integer, FactorBias> factors = new LinkedHashMap<>();
        for (int factor = 0; factor <= 5; factor++) {
            String prefix = "simulation.factor." + factor + ".";
            factors.put(factor, new FactorBias(
                bool(values, prefix + "random-bias"),
                number(values, prefix + "up-probability", 0.0, 1.0),
                positive(values, prefix + "up-amplitude-multiplier", 0.1, 3.0),
                positive(values, prefix + "down-amplitude-multiplier", 0.1, 3.0)));
        }

        double minimumScale = number(values, "simulation.correlation.minimum-scale", 0.0, 2.0);
        double maximumScale = number(values, "simulation.correlation.maximum-scale", 0.0, 2.0);
        if (maximumScale < minimumScale)
            throw new IllegalArgumentException("simulation.correlation.maximum-scale must be >= simulation.correlation.minimum-scale");
        double cycleDays = positive(values, "simulation.correlation.cycle-effective-days", 1.0, 365.0);

        return new SimulationModelConfig(defaultModel, models, instruments, factors,
            minimumScale, maximumScale, cycleDays, values);
    }

    private static InstrumentStats parseStats(
        Map<String, String> values,
        Instrument instrument,
        String profile,
        String instrumentPrefix,
        String profilePrefix
    ) {
        ValueReader read = field -> {
            String instrumentKey = instrumentPrefix + field;
            if (values.containsKey(instrumentKey))
                return new KeyValue(instrumentKey, values.get(instrumentKey));
            String profileKey = profilePrefix + field;
            return new KeyValue(profileKey, required(values, profileKey));
        };
        double alpha = number(read.get("garch-alpha"), 0.0, 0.5);
        double beta = number(read.get("garch-beta"), 0.0, 0.999);
        if (alpha + beta >= 0.999)
            throw new IllegalArgumentException(read.get("garch-beta").key() + " requires garch-alpha + garch-beta < 0.999");
        Regime longRegime = regime(read, "long");
        Regime mediumRegime = regime(read, "medium");
        Regime shortRegime = regime(read, "short");
        return new InstrumentStats(
            instrument,
            profile,
            number(read.get("annual-volatility"), 0.01, 2.0),
            number(read.get("downside-volatility-multiplier"), 0.5, 3.0),
            integer(read.get("student-t-df"), 3, 30),
            alpha,
            beta,
            number(read.get("jump-rate-per-year"), 0.0, 20.0),
            number(read.get("jump-up-min"), 0.0, 1.0),
            number(read.get("jump-up-max"), 0.0, 2.0),
            number(read.get("jump-down-min"), 0.0, 1.0),
            number(read.get("jump-down-max"), 0.0, 2.0),
            number(read.get("jump-down-probability"), 0.0, 1.0),
            number(read.get("max-step-percent"), 0.001, 0.5),
            number(read.get("idiosyncratic-variance-floor"), 0.05, 0.95),
            number(read.get("trading-days-per-year"), 1.0, 366.0),
            number(read.get("trading-hours-per-day"), 0.1, 24.0),
            longRegime,
            mediumRegime,
            shortRegime,
            new FactorLoadings(
                number(read.get("loading-cn-market"), -1.5, 1.5),
                number(read.get("loading-cn-style"), -1.5, 1.5),
                number(read.get("loading-crypto-market"), -1.5, 1.5),
                number(read.get("loading-risk-appetite"), -1.5, 1.5),
                number(read.get("loading-inflation-rate"), -1.5, 1.5)));
    }

    private static Regime regime(ValueReader read, String name) {
        KeyValue durationMin = read.get(name + "-duration-min-days");
        KeyValue durationMax = read.get(name + "-duration-max-days");
        KeyValue upMin = read.get(name + "-up-min");
        KeyValue upMax = read.get(name + "-up-max");
        KeyValue downMin = read.get(name + "-down-min");
        KeyValue downMax = read.get(name + "-down-max");
        double durationMinimum = number(durationMin, 0.1, 1000.0);
        double durationMaximum = number(durationMax, 0.1, 2000.0);
        double upMinimum = number(upMin, 0.0001, 2.0);
        double upMaximum = number(upMax, 0.0001, 2.0);
        double downMinimum = number(downMin, 0.0001, 2.0);
        double downMaximum = number(downMax, 0.0001, 2.0);
        requireOrder(durationMin, durationMinimum, durationMax, durationMaximum);
        requireOrder(upMin, upMinimum, upMax, upMaximum);
        requireOrder(downMin, downMinimum, downMax, downMaximum);
        return new Regime(durationMinimum, durationMaximum, upMinimum, upMaximum, downMinimum, downMaximum);
    }

    private static void validateKeys(Map<String, String> values) {
        Set<String> models = new LinkedHashSet<>(csv(values, "simulation.models"));
        Set<String> profiles = new LinkedHashSet<>(csv(values, "simulation.profiles"));
        Set<String> instruments = new LinkedHashSet<>();
        for (Instrument instrument : Instrument.values())
            instruments.add(instrument.commandForm());
        for (String key : values.keySet()) {
            if (!isSimulationModelKey(key)) {
                if (key.startsWith("simulation.") && !key.startsWith("simulation.default-price."))
                    throw new IllegalArgumentException("unknown simulation configuration key: " + key);
                continue;
            }
            if (key.equals("simulation.default-model") || key.equals("simulation.models") || key.equals("simulation.profiles"))
                continue;
            if (matches(key, "simulation.models.", models, MODEL_FIELDS)
                || matches(key, "simulation.profiles.", profiles, STAT_FIELDS)
                || matchesInstrument(key, instruments)
                || matchesFactor(key)
                || matchesSimple(key, "simulation.correlation.", CORRELATION_FIELDS))
                continue;
            throw new IllegalArgumentException("unknown simulation configuration key: " + key);
        }
    }

    private static boolean matches(String key, String prefix, Set<String> ids, Set<String> fields) {
        if (!key.startsWith(prefix))
            return false;
        for (String id : ids)
            if (key.equals(prefix + id + ".profile")
                || fields.stream().anyMatch(field -> key.equals(prefix + id + "." + field)))
                return true;
        return false;
    }

    private static boolean matchesInstrument(String key, Set<String> instruments) {
        String prefix = "simulation.instruments.";
        if (!key.startsWith(prefix))
            return false;
        for (String instrument : instruments) {
            String base = prefix + instrument + ".";
            if (key.equals(base + "profile"))
                return true;
            for (String field : STAT_FIELDS)
                if (key.equals(base + field))
                    return true;
        }
        return false;
    }

    private static boolean matchesFactor(String key) {
        for (int factor = 0; factor <= 5; factor++)
            for (String field : FACTOR_FIELDS)
                if (key.equals("simulation.factor." + factor + "." + field))
                    return true;
        return false;
    }

    private static boolean matchesSimple(String key, String prefix, Set<String> fields) {
        for (String field : fields)
            if (key.equals(prefix + field))
                return true;
        return false;
    }

    private static List<String> csv(Map<String, String> values, String key) {
        String value = required(values, key);
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (normalized.isEmpty())
                throw new IllegalArgumentException(key + " contains an empty value");
            if (!normalized.matches("[a-z0-9-]+"))
                throw new IllegalArgumentException(key + " contains invalid id: " + normalized);
            if (result.contains(normalized))
                throw new IllegalArgumentException(key + " contains duplicate id: " + normalized);
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("missing simulation configuration key: " + key);
        return value.trim();
    }

    private static double positive(Map<String, String> values, String key, double minimum, double maximum) {
        return number(new KeyValue(key, required(values, key)), minimum, maximum);
    }

    private static double number(Map<String, String> values, String key, double minimum, double maximum) {
        return number(new KeyValue(key, required(values, key)), minimum, maximum);
    }

    private static double number(KeyValue value, double minimum, double maximum) {
        final double parsed;
        try {
            parsed = Double.parseDouble(value.value());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(value.key() + " is not a number: " + value.value());
        }
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum)
            throw new IllegalArgumentException(value.key() + " must be between " + minimum + " and " + maximum + ": " + value.value());
        return parsed;
    }

    private static int integer(Map<String, String> values, String key, int minimum, int maximum) {
        return integer(new KeyValue(key, required(values, key)), minimum, maximum);
    }

    private static int integer(KeyValue value, int minimum, int maximum) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value.value());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(value.key() + " is not an integer: " + value.value());
        }
        if (parsed < minimum || parsed > maximum)
            throw new IllegalArgumentException(value.key() + " must be between " + minimum + " and " + maximum + ": " + value.value());
        return parsed;
    }

    private static boolean bool(Map<String, String> values, String key) {
        String value = required(values, key).toLowerCase(Locale.ROOT);
        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(key + " must be true or false: " + value);
        return Boolean.parseBoolean(value);
    }

    private static void requireOrder(KeyValue minimumKey, double minimum, KeyValue maximumKey, double maximum) {
        if (maximum < minimum)
            throw new IllegalArgumentException(maximumKey.key() + " must be >= " + minimumKey.key());
    }

    @FunctionalInterface
    private interface ValueReader {
        KeyValue get(String field);
    }

    private record KeyValue(String key, String value) {
    }
}
