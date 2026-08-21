package com.imyvm.finance.quote;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.QuoteOrigin;
import com.imyvm.finance.storage.StoredQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

public final class SimulatedQuoteGenerator {
    public record Step(MarketQuote quote, double trendState, long randomBps, long trendBps, double longBps, double mediumBps, double shortBps, double unclampedBps) { }

    private SimulatedQuoteGenerator() { }

    public static long intervalToleranceMillis(long expected) { return Math.max(45_000L, expected / 4L); }

    public static Step nextStep(Instrument instrument, MarketQuote previous, long seed, int iteration, int factor,
                               double trendState, String formulaText) {
        return nextStep(instrument, previous, seed, iteration, factor, trendState, Map.of("LONG", formulaText, "MEDIUM", "0", "SHORT", "0"));
    }

    public static Step nextStep(Instrument instrument, MarketQuote previous, long seed, int iteration, int factor,
                               double trendState, Map<String, String> formulas) {
        String formulaText = formulas.get("LONG");
        SplittableRandom random = new SplittableRandom(seed ^ ((long) instrument.ordinal() * 0xBF58476D1CE4E5B9L) ^ iteration);
        double triangular = random.nextDouble() + random.nextDouble() - 1.0;
        boolean crypto = "CRYPTO".equals(instrument.market());
        long volatilityBps = crypto ? 35L : 8L;
        long maxMoveBps = crypto ? 250L : 60L;
        long targetBps = switch (factor) {
            case 5 -> 10L;
            case 4 -> 4L;
            case 3 -> 0L;
            case 2 -> -4L;
            case 1 -> -10L;
            default -> 0L;
        };
        double shock = random.nextDouble() < 0.0008
            ? (random.nextBoolean() ? 1 : -1) * (crypto ? 12.0 : 4.0) : 0.0;
        double nextTrendState = clamp(trendState * 0.999 + shock + triangular * 0.02, -40.0, 40.0);
        long trendBps = Math.round(targetBps + nextTrendState);
        Map<String, Double> inputs = Map.of(
            "PREV_PRICE", (double) previous.priceScaled(), "PREV_LOG_RETURN", previous.changeBps() / 10_000.0,
            "TREND_BPS", (double) trendBps,
            "VOLATILITY_BPS", (double) volatilityBps, "MAX_MOVE_BPS", (double) maxMoveBps,
            "RANDOM", triangular, "ITERATION", (double) iteration, "HISTORY_COUNT", 0.0);
        double longBps = SimulationFormula.compile(formulaText).eval(inputs);
        double mediumBps = SimulationFormula.compile(formulas.get("MEDIUM")).eval(inputs);
        double shortBps = SimulationFormula.compile(formulas.get("SHORT")).eval(inputs);
        double unclampedBps = longBps + mediumBps + shortBps;
        double moveBps = clamp(unclampedBps, -maxMoveBps, maxMoveBps);
        long price = Math.max(0L, BigDecimal.valueOf(previous.priceScaled() * Math.exp(moveBps / 10_000.0))
            .setScale(0, RoundingMode.HALF_UP).longValue());
        MarketQuote quote = new MarketQuote(instrument, previous.name(), price,
            BigDecimal.valueOf(moveBps).setScale(0, RoundingMode.HALF_UP).longValue(), previous.status(), QuoteOrigin.SIMULATED);
        return new Step(quote, nextTrendState, Math.round(triangular * volatilityBps), trendBps, longBps, mediumBps, shortBps, unclampedBps);
    }

    private static double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }
}
