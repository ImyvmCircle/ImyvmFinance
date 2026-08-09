package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import com.imyvm.finance.ImyvmFinance;
import com.imyvm.finance.storage.StoredQuote;
import com.imyvm.finance.economy.EconomySettlementResult;
import com.imyvm.finance.transaction.StockOperation;
import com.imyvm.finance.transaction.StockTransaction;
import com.imyvm.finance.transaction.StockTransactionState;
import com.imyvm.finance.trading.TradeCalculator;
import com.imyvm.finance.trading.TradeEstimate;
import com.imyvm.finance.trading.TradeSide;
import com.imyvm.finance.trading.TradeValidationException;
import com.imyvm.finance.trading.TradeValidator;
import com.imyvm.finance.trading.TradingRules;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;

import java.util.function.Supplier;

public final class MarketCommands {
    private MarketCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
        var buy = Commands.literal("buy")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("symbol", StringArgumentType.word())
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .executes(MarketCommands::buy)));

        var estimate = Commands.literal("estimate")
            .then(Commands.argument("symbol", StringArgumentType.word())
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .executes(MarketCommands::estimate)));

        dispatcher.register(Commands.literal("market")
            .then(Commands.literal("list")
                .executes(MarketCommands::list))
            .then(Commands.literal("quote")
                .then(Commands.argument("symbol", StringArgumentType.word())
                    .executes(MarketCommands::quote)))
            .then(estimate));
    }

    private static int list(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        MutableComponent message = Component.empty()
            .append(Translator.tr("commands.market.list.header"));
        for (Instrument instrument : Instrument.values()) {
            message.append("\n")
                .append(Translator.tr(
                    "commands.market.list.item",
                    instrument.symbol(),
                    instrument.market()));
        }

        context.getSource().sendSuccess((Supplier<Component>) () -> message, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int quote(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        Instrument instrument = Instrument.fromSymbol(StringArgumentType.getString(context, "symbol"));
        if (instrument == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unknown_symbol"));
            return 0;
        }

        if (ImyvmFinance.QUOTE_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.storage_unavailable"));
            return 0;
        }

        Optional<StoredQuote> storedQuote;
        try {
            storedQuote = ImyvmFinance.QUOTE_STORE.findLatest(instrument);
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.storage_unavailable"));
            return 0;
        }

        if (storedQuote.isEmpty()) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.symbol()));
            return 0;
        }

        StoredQuote quote = storedQuote.get();
        context.getSource().sendSuccess(
            () -> Translator.tr(
                "commands.market.quote.result",
                quote.quote().instrument().symbol(),
                formatPrice(quote.quote().priceScaled()),
                formatPercent(quote.quote().changeBps()),
                quote.source()),
            false);
        return Command.SINGLE_SUCCESS;
    }

    private static int estimate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        Instrument instrument = Instrument.fromSymbol(StringArgumentType.getString(context, "symbol"));
        long units = LongArgumentType.getLong(context, "units");
        if (instrument == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unknown_symbol"));
            return 0;
        }
        if (ImyvmFinance.QUOTE_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.storage_unavailable"));
            return 0;
        }

        try {
            Optional<StoredQuote> storedQuote = ImyvmFinance.QUOTE_STORE.findLatest(instrument);
            if (storedQuote.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.symbol()));
                return 0;
            }
            TradeEstimate estimate = TradeCalculator.estimate(
                TradeSide.BUY, storedQuote.get(), units, System.currentTimeMillis(), TradingRules.DEFAULT);
            context.getSource().sendSuccess(
                () -> Translator.tr(
                    "commands.market.estimate.result",
                    estimate.instrument().symbol(),
                    estimate.units(),
                    formatPrice(estimate.executionPriceScaled()),
                    estimate.feeAmount(),
                    estimate.settlementAmount(),
                    estimate.slippageBps(),
                    estimate.snapshotId()),
                false);
            return Command.SINGLE_SUCCESS;
        } catch (TradeValidationException exception) {
            context.getSource().sendFailure(
                Translator.tr(exception.messageKey(), exception.messageArguments()));
            return 0;
        } catch (ArithmeticException exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.trade.amount_too_large"));
            return 0;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.storage_unavailable"));
            return 0;
        }
    }

    private static int buy(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        Instrument instrument = Instrument.fromSymbol(StringArgumentType.getString(context, "symbol"));
        long units = LongArgumentType.getLong(context, "units");
        if (instrument == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unknown_symbol"));
            return 0;
        }
        if (ImyvmFinance.QUOTE_STORE == null
            || ImyvmFinance.TRANSACTION_STORE == null
            || ImyvmFinance.TRADING_STORE == null
            || ImyvmFinance.ECONOMY_SETTLEMENT == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.buy.storage_unavailable"));
            return 0;
        }

        long now = System.currentTimeMillis();
        try {
            Optional<StoredQuote> storedQuote = ImyvmFinance.QUOTE_STORE.findLatest(instrument);
            if (storedQuote.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.symbol()));
                return 0;
            }
            TradeEstimate estimate = TradeCalculator.estimate(
                TradeSide.BUY, storedQuote.get(), units, now, TradingRules.DEFAULT);
            ZoneId zone = ZoneId.systemDefault();
            LocalDate date = LocalDate.now(zone);
            long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            TradeValidator.validateBuy(
                estimate,
                ImyvmFinance.TRADING_STORE.dailyBuyAmount(player.getUUID(), dayStart, dayEnd),
                ImyvmFinance.TRADING_STORE.positionValue(player.getUUID()),
                TradingRules.DEFAULT);

            UUID orderId = UUID.randomUUID();
            UUID transactionId = UUID.randomUUID();
            StockTransaction transaction = new StockTransaction(
                transactionId,
                player.getUUID(),
                StockOperation.BUY,
                orderId.toString(),
                instrument,
                estimate.settlementAmount(),
                StockTransactionState.PREPARED,
                null,
                now,
                now);
            ImyvmFinance.TRANSACTION_STORE.createPrepared(transaction);
            try {
                ImyvmFinance.TRADING_STORE.createPendingBuy(
                    orderId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    transaction,
                    estimate,
                    now,
                    now + TradingRules.DEFAULT.sellCooldownMillis());
            } catch (Exception exception) {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId, StockTransactionState.CANCELLED, "finance_prepare_failed", now);
                context.getSource().sendFailure(Translator.tr("commands.market.buy.storage_unavailable"));
                return 0;
            }

            EconomySettlementResult settlement = ImyvmFinance.ECONOMY_SETTLEMENT.settle(player, transaction);
            if (settlement.state() == StockTransactionState.CANCELLED) {
                ImyvmFinance.TRADING_STORE.cancel(transactionId);
                context.getSource().sendFailure(
                    Translator.tr("commands.market.buy.insufficient_balance", estimate.settlementAmount()));
                return 0;
            }
            if (settlement.state() != StockTransactionState.ECONOMY_CONFIRMED) {
                ImyvmFinance.TRADING_STORE.markPendingManual(transactionId);
                context.getSource().sendFailure(Translator.tr("commands.market.buy.pending_manual"));
                return 0;
            }

            try {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId, StockTransactionState.FINANCE_CONFIRMED, "finance_confirmed", System.currentTimeMillis());
                ImyvmFinance.TRADING_STORE.activateBuy(transactionId);
            } catch (Exception exception) {
                try {
                    ImyvmFinance.TRANSACTION_STORE.transition(
                        transactionId, StockTransactionState.PENDING_MANUAL, "finance_activation_uncertain", System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                try {
                    ImyvmFinance.TRADING_STORE.markPendingManual(transactionId);
                } catch (Exception ignored) {
                }
                context.getSource().sendFailure(Translator.tr("commands.market.buy.pending_manual"));
                return 0;
            }

            context.getSource().sendSuccess(
                () -> Translator.tr(
                    "commands.market.buy.success",
                    estimate.instrument().symbol(),
                    estimate.units(),
                    estimate.settlementAmount(),
                    formatPrice(estimate.executionPriceScaled()),
                    estimate.feeAmount(),
                    estimate.snapshotId()),
                false);
            return Command.SINGLE_SUCCESS;
        } catch (TradeValidationException exception) {
            context.getSource().sendFailure(
                Translator.tr(exception.messageKey(), exception.messageArguments()));
            return 0;
        } catch (ArithmeticException exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.trade.amount_too_large"));
            return 0;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.buy.storage_unavailable"));
            return 0;
        }
    }

    private static String formatPrice(long priceScaled) {
        return BigDecimal.valueOf(priceScaled, 4).stripTrailingZeros().toPlainString();
    }

    private static String formatPercent(long changeBps) {
        return BigDecimal.valueOf(changeBps, 2)
            .setScale(2, RoundingMode.UNNECESSARY)
            .toPlainString() + "%";
    }
}
