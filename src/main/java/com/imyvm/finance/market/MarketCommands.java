package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import com.imyvm.finance.ImyvmFinance;
import com.imyvm.finance.storage.StoredQuote;
import com.imyvm.finance.trading.TradeCalculator;
import com.imyvm.finance.trading.TradeEstimate;
import com.imyvm.finance.trading.TradeSide;
import com.imyvm.finance.trading.TradeValidationException;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import java.util.function.Supplier;

public final class MarketCommands {
    private MarketCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
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

    private static String formatPrice(long priceScaled) {
        return BigDecimal.valueOf(priceScaled, 4).stripTrailingZeros().toPlainString();
    }

    private static String formatPercent(long changeBps) {
        return BigDecimal.valueOf(changeBps, 2)
            .setScale(2, RoundingMode.UNNECESSARY)
            .toPlainString() + "%";
    }
}
