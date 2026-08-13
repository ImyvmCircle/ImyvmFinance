package com.imyvm.finance.trading;

public final class TradeValidator {
    private TradeValidator() {
    }

    public static void validateBuy(TradeEstimate estimate,
                                   long dailyBuyUsed,
                                   long currentPositionValue,
                                   TradingRules rules)
        throws TradeValidationException {
        if (estimate.side() != TradeSide.BUY)
            throw new IllegalArgumentException("buy validation requires a buy estimate");
        if (Math.addExact(dailyBuyUsed, estimate.settlementAmount()) > rules.dailyBuyLimit())
            throw new TradeValidationException("commands.market.trade.daily_buy_limit");
        if (Math.addExact(currentPositionValue, estimate.grossAmount()) > rules.positionValueLimit())
            throw new TradeValidationException("commands.market.trade.position_value_limit");
    }

    public static void validateSell(TradeEstimate estimate,
                                    StockPositionView position,
                                    long quoteFetchedAtEpochMillis,
                                    long nowEpochMillis,
                                    long dailySellUsed,
                                    TradingRules rules)
        throws TradeValidationException {
        if (estimate.side() != TradeSide.SELL)
            throw new IllegalArgumentException("sell validation requires a sell estimate");
        if (position.instrument() != estimate.instrument())
            throw new TradeValidationException("commands.market.trade.position_instrument_mismatch");
        if (position.remainingUnits() - position.frozenUnits() < estimate.units())
            throw new TradeValidationException("commands.market.trade.position_units_unavailable");
        if (nowEpochMillis < position.earliestSellAtEpochMillis())
            throw new TradeValidationException(
                "commands.market.trade.sell_cooldown",
                position.earliestSellAtEpochMillis());
        if (quoteFetchedAtEpochMillis <= position.boughtAtEpochMillis())
            throw new TradeValidationException("commands.market.trade.same_snapshot");
        if (Math.addExact(dailySellUsed, estimate.settlementAmount()) > rules.dailySellLimit())
            throw new TradeValidationException("commands.market.trade.daily_sell_limit");
    }
}
