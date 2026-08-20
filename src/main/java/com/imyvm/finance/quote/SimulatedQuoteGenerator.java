package com.imyvm.finance.quote;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.QuoteOrigin;
import com.imyvm.finance.storage.StoredQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class SimulatedQuoteGenerator {
    private SimulatedQuoteGenerator() {
    }
    public static List<StoredQuote> selectEligible(List<StoredQuote> history, long intervalMillis) {
        if (history == null || history.size() < 5) return List.of();
        for (int start = 0; start + 5 <= history.size(); start++) {
            List<StoredQuote> window = history.subList(start, start + 5);
            boolean valid = true;
            for (int index = 1; index < 5; index++) {
                if (window.get(index).nodeTimeEpochMillis() - window.get(index - 1).nodeTimeEpochMillis() != intervalMillis
                    || window.get(index).quote().origin() != QuoteOrigin.REAL) { valid = false; break; }
            }
            if (valid && window.getFirst().quote().origin() == QuoteOrigin.REAL) return List.copyOf(window);
        }
        return List.of();
    }


    public static boolean eligible(List<StoredQuote> history, long intervalMillis) {
        if (history == null || history.size() < 5)
            return false;
        for (int index = 1; index < 5; index++) {
            long previous = history.get(index - 1).nodeTimeEpochMillis();
            long current = history.get(index).nodeTimeEpochMillis();
            if (current - previous != intervalMillis)
                return false;
            if (history.get(index).quote().origin() != QuoteOrigin.REAL)
                return false;
        }
        return history.getFirst().quote().origin() == QuoteOrigin.REAL;
    }

    public static MarketQuote next(Instrument instrument, List<StoredQuote> history,
                                   MarketQuote previous, long seed, long sessionId, int iteration) {
        return next(instrument, history, previous, seed, sessionId, iteration, SimulationFormula.DEFAULT);
    }

    public static MarketQuote next(Instrument instrument, List<StoredQuote> history,
                                   MarketQuote previous, long seed, long sessionId, int iteration, String formulaText) {
        history = selectEligible(history, history.get(1).nodeTimeEpochMillis() - history.get(0).nodeTimeEpochMillis());
        if (history.size() != 5)
            throw new IllegalArgumentException("five consecutive real quote nodes are required");
        List<Double> returns = new ArrayList<>();
        for (int index = 1; index < 5; index++)
            returns.add(Math.log((double) history.get(index).quote().priceScaled() / history.get(index - 1).quote().priceScaled()));
        double drift = median(returns);
        double deviation = median(returns.stream().map(value -> Math.abs(value - drift)).toList()) * 1.4826;
        double maxMove = Math.max(0.0001, returns.stream().mapToDouble(Math::abs).max().orElse(0.0001) * 1.5);
        long mixedSeed = seed ^ ((long) instrument.ordinal() * 0xBF58476D1CE4E5B9L) ^ iteration;
        SplittableRandom random = new SplittableRandom(mixedSeed);
        double triangular = (random.nextDouble() + random.nextDouble()) - 1.0;
        double move = SimulationFormula.parse(formulaText).eval(java.util.Map.of(
            "PREV_PRICE", (double) previous.priceScaled(), "PREV_LOG_RETURN", returns.getLast() * 10_000.0,
            "DRIFT_BPS", drift * 10_000.0, "VOLATILITY_BPS", deviation * 10_000.0,
            "MAX_MOVE_BPS", maxMove * 10_000.0, "RANDOM", triangular,
            "ITERATION", (double) iteration, "HISTORY_COUNT", (double) history.size()));
        move = Math.max(-maxMove, Math.min(maxMove, move / 10_000.0));
        double price = previous.priceScaled() * Math.exp(move);
        long scaled = Math.max(0L, BigDecimal.valueOf(price).setScale(0, RoundingMode.HALF_UP).longValue());
        long changeBps = BigDecimal.valueOf(move * 10_000.0).setScale(0, RoundingMode.HALF_UP).longValue();
        return new MarketQuote(instrument, previous.name(), scaled, changeBps,
            previous.status(), QuoteOrigin.SIMULATED);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }
}
