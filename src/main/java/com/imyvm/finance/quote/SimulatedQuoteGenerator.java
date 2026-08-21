package com.imyvm.finance.quote;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.QuoteOrigin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class SimulatedQuoteGenerator {
    private static final long CN_MARKET_SALT = 0x243F6A8885A308D3L;
    private static final long CN_STYLE_SALT = 0x13198A2E03707344L;
    private static final long CRYPTO_MARKET_SALT = 0xA4093822299F31D0L;
    private static final long RISK_APPETITE_SALT = 0x082EFA98EC4E6C89L;
    private static final long INFLATION_RATE_SALT = 0x452821E638D01377L;
    private static final long IDIOSYNCRATIC_SALT = 0xBE5466CF34E90C6CL;
    private static final long LONG_SALT = 0xC0AC29B7C97C50DDL;
    private static final long MEDIUM_SALT = 0x3F84D5B5B5470917L;
    private static final long SHORT_SALT = 0x9216D5D98979FB1BL;
    private static final long JUMP_SALT = 0xD1310BA698DFB5ACL;

    public record RegimeState(double durationDays, double elapsedDays, double signedAmplitude) {
    }

    public record State(
        double varianceMultiplier,
        double previousShock,
        RegimeState longRegime,
        RegimeState mediumRegime,
        RegimeState shortRegime
    ) {
        public static State initial() {
            RegimeState empty = new RegimeState(0.0, 0.0, 0.0);
            return new State(1.0, 0.0, empty, empty, empty);
        }

        public String serialize() {
            return "v1;" + varianceMultiplier + ";" + previousShock + ";"
                + encode(longRegime) + ";" + encode(mediumRegime) + ";" + encode(shortRegime);
        }

        public static State parse(String value) {
            if (value == null || value.isBlank())
                return initial();
            String[] parts = value.split(";", -1);
            if (parts.length != 6 || !parts[0].equals("v1"))
                throw new IllegalArgumentException("invalid simulation state version");
            try {
                return new State(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    decode(parts[3]), decode(parts[4]), decode(parts[5]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid simulation state payload", exception);
            }
        }

        private static String encode(RegimeState state) {
            return state.durationDays() + "," + state.elapsedDays() + "," + state.signedAmplitude();
        }

        private static RegimeState decode(String value) {
            String[] fields = value.split(",", -1);
            if (fields.length != 3)
                throw new IllegalArgumentException("invalid regime state");
            return new RegimeState(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]));
        }
    }

    public record Step(
        MarketQuote quote,
        State state,
        double longBps,
        double mediumBps,
        double shortBps,
        double stochasticBps,
        double jumpBps,
        double unclampedBps,
        double appliedBps,
        double correlationScale,
        double varianceMultiplier,
        List<String> switches
    ) {
    }

    private record RegimeAdvance(RegimeState state, double returnValue, String switchEvent) {
    }

    private SimulatedQuoteGenerator() {
    }

    public static long intervalToleranceMillis(long expected) {
        return Math.max(45_000L, expected / 4L);
    }

    public static Step nextStep(
        Instrument instrument,
        MarketQuote previous,
        long seed,
        int iteration,
        int factor,
        State state,
        SimulationModelConfig config,
        String modelId,
        long intervalMillis
    ) {
        if (intervalMillis <= 0)
            throw new IllegalArgumentException("simulation interval must be positive");
        SimulationModelConfig.ModelSuite model = config.model(modelId);
        SimulationModelConfig.InstrumentStats stats = config.instrument(instrument);
        State current = state == null ? State.initial() : state;
        double intervalMinutes = intervalMillis / 60_000.0;
        double effectiveDays = intervalMinutes / (stats.tradingHoursPerDay() * 60.0);
        double years = intervalMinutes / stats.effectiveMinutesPerYear();

        RegimeAdvance longAdvance = advanceRegime(current.longRegime(), stats.longRegime(),
            model, config.factorBias(factor), seed, instrument, iteration, effectiveDays, LONG_SALT);
        RegimeAdvance mediumAdvance = advanceRegime(current.mediumRegime(), stats.mediumRegime(),
            model, null, seed, instrument, iteration, effectiveDays, MEDIUM_SALT);
        RegimeAdvance shortAdvance = advanceRegime(current.shortRegime(), stats.shortRegime(),
            model, null, seed, instrument, iteration, effectiveDays, SHORT_SALT);

        double correlationScale = correlationScale(config, seed, iteration, effectiveDays);
        double combinedShock = combinedShock(config, model, stats, seed, instrument, iteration,
            correlationScale);
        double downsideLeverage = current.previousShock() < 0.0
            ? Math.pow(stats.downsideVolatilityMultiplier() * model.downsideMultiplier(), 2.0)
            : 1.0;
        double omega = 1.0 - stats.garchAlpha() - stats.garchBeta();
        double variance = clamp(omega
            + stats.garchAlpha() * current.previousShock() * current.previousShock() * downsideLeverage
            + stats.garchBeta() * current.varianceMultiplier(), 0.08, 12.0);
        double stochasticReturn = combinedShock * stats.annualVolatility()
            * model.volatilityMultiplier() * Math.sqrt(years * variance);
        double jumpReturn = jumpReturn(stats, model, seed, instrument, iteration, years);

        double longReturn = longAdvance.returnValue();
        double mediumReturn = mediumAdvance.returnValue();
        double shortReturn = shortAdvance.returnValue();
        double totalReturn = longReturn + mediumReturn + shortReturn + stochasticReturn + jumpReturn;
        double maximumLogMove = Math.log1p(stats.maxStepPercent());
        double appliedReturn = clamp(totalReturn, -maximumLogMove, maximumLogMove);
        long price = Math.max(1L, BigDecimal.valueOf(previous.priceScaled() * Math.exp(appliedReturn))
            .setScale(0, RoundingMode.HALF_UP).longValue());
        long changeBps = BigDecimal.valueOf(appliedReturn * 10_000.0)
            .setScale(0, RoundingMode.HALF_UP).longValue();
        MarketQuote quote = new MarketQuote(instrument, previous.name(), price, changeBps,
            previous.status(), QuoteOrigin.SIMULATED);

        List<String> switches = new ArrayList<>();
        if (longAdvance.switchEvent() != null)
            switches.add("LONG:" + longAdvance.switchEvent());
        if (mediumAdvance.switchEvent() != null)
            switches.add("MEDIUM:" + mediumAdvance.switchEvent());
        if (shortAdvance.switchEvent() != null)
            switches.add("SHORT:" + shortAdvance.switchEvent());
        State nextState = new State(variance, combinedShock, longAdvance.state(),
            mediumAdvance.state(), shortAdvance.state());
        return new Step(quote, nextState, longReturn * 10_000.0, mediumReturn * 10_000.0,
            shortReturn * 10_000.0, stochasticReturn * 10_000.0, jumpReturn * 10_000.0,
            totalReturn * 10_000.0, appliedReturn * 10_000.0, correlationScale, variance,
            List.copyOf(switches));
    }

    private static RegimeAdvance advanceRegime(
        RegimeState previous,
        SimulationModelConfig.Regime parameters,
        SimulationModelConfig.ModelSuite model,
        SimulationModelConfig.FactorBias factor,
        long seed,
        Instrument instrument,
        int iteration,
        double effectiveDays,
        long salt
    ) {
        RegimeState regime = previous;
        String event = null;
        if (regime == null || regime.durationDays() <= 0.0
            || regime.elapsedDays() >= regime.durationDays()) {
            SplittableRandom random = random(seed, instrument, iteration, salt);
            double duration = uniform(random, parameters.durationMinDays(),
                parameters.durationMaxDays()) * model.durationMultiplier();
            double upProbability = factor == null ? 0.5 : factor.randomBias()
                ? uniform(random, 0.2, 0.8) : factor.upProbability();
            boolean up = random.nextDouble() < upProbability;
            double minimum = up ? parameters.upMin() : parameters.downMin();
            double maximum = up ? parameters.upMax() : parameters.downMax();
            double factorMultiplier = factor == null ? 1.0 : up
                ? factor.upAmplitudeMultiplier() : factor.downAmplitudeMultiplier();
            double amplitudeMagnitude = Math.log1p(uniform(random, minimum, maximum)
                * factorMultiplier * model.trendMultiplier());
            double elapsed = previous != null && previous.durationDays() > 0.0
                ? 0.0 : random.nextDouble() * duration;
            regime = new RegimeState(duration, elapsed, up ? amplitudeMagnitude : -amplitudeMagnitude);
            event = up ? "UP" : "DOWN";
        }
        double progress = clamp(regime.elapsedDays() / regime.durationDays(), 0.0, 1.0);
        double shape = 0.35 + 0.65 * (1.0 - Math.cos(progress * Math.PI * 2.0));
        double returnValue = regime.signedAmplitude() / regime.durationDays() * shape * effectiveDays;
        RegimeState next = new RegimeState(regime.durationDays(),
            Math.min(regime.durationDays(), regime.elapsedDays() + effectiveDays),
            regime.signedAmplitude());
        return new RegimeAdvance(next, returnValue, event);
    }

    private static double combinedShock(
        SimulationModelConfig config,
        SimulationModelConfig.ModelSuite model,
        SimulationModelConfig.InstrumentStats stats,
        long seed,
        Instrument instrument,
        int iteration,
        double correlationScale
    ) {
        double multiplier = correlationScale * model.commonFactorMultiplier();
        double[] loadings = stats.loadings().values();
        double[] factors = {
            sharedShock(seed, iteration, CN_MARKET_SALT),
            sharedShock(seed, iteration, CN_STYLE_SALT),
            sharedShock(seed, iteration, CRYPTO_MARKET_SALT),
            sharedShock(seed, iteration, RISK_APPETITE_SALT),
            sharedShock(seed, iteration, INFLATION_RATE_SALT)
        };
        double commonVariance = 0.0;
        for (int index = 0; index < loadings.length; index++) {
            loadings[index] *= multiplier;
            commonVariance += loadings[index] * loadings[index];
        }
        double maximumCommonVariance = 1.0 - stats.idiosyncraticVarianceFloor();
        if (commonVariance > maximumCommonVariance) {
            double normalization = Math.sqrt(maximumCommonVariance / commonVariance);
            commonVariance = 0.0;
            for (int index = 0; index < loadings.length; index++) {
                loadings[index] *= normalization;
                commonVariance += loadings[index] * loadings[index];
            }
        }
        double result = 0.0;
        for (int index = 0; index < loadings.length; index++)
            result += loadings[index] * factors[index];
        int degrees = Math.max(3, stats.studentTDf() + model.studentTDfAdjustment());
        double idiosyncratic = studentT(random(seed, instrument, iteration, IDIOSYNCRATIC_SALT),
            degrees);
        return result + Math.sqrt(Math.max(0.0, 1.0 - commonVariance)) * idiosyncratic;
    }

    private static double sharedShock(long seed, int iteration, long salt) {
        return studentT(new SplittableRandom(mix(seed ^ salt
            ^ ((long) iteration * 0x9E3779B97F4A7C15L))), 6);
    }

    private static double correlationScale(
        SimulationModelConfig config,
        long seed,
        int iteration,
        double effectiveDays
    ) {
        double cycle = config.correlationCycleEffectiveDays();
        double position = iteration * effectiveDays / cycle;
        long segment = (long) Math.floor(position);
        double phase = position - segment;
        double from = correlationTarget(config, seed, segment);
        double to = correlationTarget(config, seed, segment + 1L);
        double smooth = phase * phase * (3.0 - 2.0 * phase);
        return from + (to - from) * smooth;
    }

    private static double correlationTarget(SimulationModelConfig config, long seed, long segment) {
        SplittableRandom random = new SplittableRandom(mix(seed ^ RISK_APPETITE_SALT
            ^ (segment * 0xBF58476D1CE4E5B9L)));
        return uniform(random, config.correlationMinimumScale(),
            config.correlationMaximumScale());
    }

    private static double jumpReturn(
        SimulationModelConfig.InstrumentStats stats,
        SimulationModelConfig.ModelSuite model,
        long seed,
        Instrument instrument,
        int iteration,
        double years
    ) {
        double probability = 1.0 - Math.exp(-stats.jumpRatePerYear()
            * model.jumpMultiplier() * years);
        double expected = probability * (
            (1.0 - stats.jumpDownProbability()) * meanLog1p(stats.jumpUpMin(), stats.jumpUpMax())
                - stats.jumpDownProbability() * meanLog1p(stats.jumpDownMin(), stats.jumpDownMax()));
        SplittableRandom random = random(seed, instrument, iteration, JUMP_SALT);
        if (random.nextDouble() >= probability)
            return -expected;
        boolean down = random.nextDouble() < stats.jumpDownProbability();
        double magnitude = down
            ? uniform(random, stats.jumpDownMin(), stats.jumpDownMax())
            : uniform(random, stats.jumpUpMin(), stats.jumpUpMax());
        return (down ? -Math.log1p(magnitude) : Math.log1p(magnitude)) - expected;
    }

    private static double meanLog1p(double minimum, double maximum) {
        if (minimum == maximum)
            return Math.log1p(minimum);
        return (logIntegral(maximum) - logIntegral(minimum)) / (maximum - minimum);
    }

    private static double logIntegral(double value) {
        return (1.0 + value) * Math.log1p(value) - (1.0 + value);
    }

    private static double studentT(SplittableRandom random, int degrees) {
        double numerator = normal(random);
        double denominator = 0.0;
        for (int index = 0; index < degrees; index++) {
            double value = normal(random);
            denominator += value * value;
        }
        return numerator / Math.sqrt(denominator / degrees)
            * Math.sqrt((degrees - 2.0) / degrees);
    }

    private static double normal(SplittableRandom random) {
        double first = Math.max(Double.MIN_NORMAL, random.nextDouble());
        return Math.sqrt(-2.0 * Math.log(first))
            * Math.cos(2.0 * Math.PI * random.nextDouble());
    }

    private static SplittableRandom random(
        long seed,
        Instrument instrument,
        int iteration,
        long salt
    ) {
        return new SplittableRandom(mix(seed ^ salt
            ^ ((long) instrument.ordinal() * 0x94D049BB133111EBL)
            ^ ((long) iteration * 0x9E3779B97F4A7C15L)));
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double uniform(SplittableRandom random, double minimum, double maximum) {
        return minimum + random.nextDouble() * (maximum - minimum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
