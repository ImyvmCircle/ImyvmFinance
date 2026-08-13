package com.imyvm.finance.trading;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.storage.StoredQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TradeCalculator {
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
    private static final BigDecimal UNIT_SCALE = BigDecimal.TEN;

    private TradeCalculator() {
    }

    public static TradeEstimate estimate(TradeSide side,
                                          StoredQuote storedQuote,
                                          long units,
                                          long nowEpochMillis,
                                          TradingRules rules)
        throws TradeValidationException {
        if (units < rules.minUnits())
            throw new TradeValidationException(
                "commands.market.trade.invalid_units", rules.minUnits());
        if (storedQuote.quote().status() != com.imyvm.finance.market.MarketStatus.OPEN)
            throw new TradeValidationException(
                "commands.market.trade.market_not_open",
                storedQuote.quote().instrument().symbol());
        if (storedQuote.marketTimeEpochMillis() > nowEpochMillis
            || nowEpochMillis - storedQuote.marketTimeEpochMillis() > rules.maxQuoteAgeMillis())
            throw new TradeValidationException(
                "commands.market.trade.quote_stale",
                storedQuote.quote().instrument().symbol());

        Instrument instrument = storedQuote.quote().instrument();
        BigDecimal indexPrice = BigDecimal.valueOf(storedQuote.quote().priceScaled(), 4);
        BigDecimal shares = BigDecimal.valueOf(units).divide(UNIT_SCALE);
        BigDecimal baseValue = indexPrice.multiply(shares);
        long estimatedValue = money(baseValue, RoundingMode.CEILING);
        int slippageBps = rules.baseSlippageBps() + extraSlippageBps(estimatedValue);
        BigDecimal slippage = BigDecimal.valueOf(slippageBps).divide(TEN_THOUSAND);
        BigDecimal executionPrice = side == TradeSide.BUY
            ? indexPrice.multiply(BigDecimal.ONE.add(slippage))
            : indexPrice.multiply(BigDecimal.ONE.subtract(slippage));
        BigDecimal gross = executionPrice.multiply(shares);
        long grossAmount = money(
            gross,
            side == TradeSide.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR);
        long feeAmount = money(
            gross.multiply(BigDecimal.valueOf(rules.feeBps()).divide(TEN_THOUSAND)),
            RoundingMode.CEILING);
        long settlementAmount = side == TradeSide.BUY
            ? Math.addExact(grossAmount, feeAmount)
            : Math.max(0, grossAmount - feeAmount);

        long executionPriceScaled = executionPrice
            .multiply(TEN_THOUSAND)
            .setScale(0, side == TradeSide.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR)
            .longValueExact();

        return new TradeEstimate(
            side,
            instrument,
            units,
            storedQuote.snapshotId(),
            executionPriceScaled,
            grossAmount,
            feeAmount,
            settlementAmount,
            slippageBps,
            rules.feeBps());
    }

    private static int extraSlippageBps(long estimatedValue) {
        if (estimatedValue >= 100_000)
            return 60;
        if (estimatedValue >= 50_000)
            return 30;
        if (estimatedValue >= 10_000)
            return 10;
        return 0;
    }

    private static long money(BigDecimal value, RoundingMode roundingMode) {
        return value.setScale(0, roundingMode).longValueExact();
    }
}
