package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import com.imyvm.finance.ImyvmFinance;
import com.imyvm.finance.storage.StoredQuote;
import com.imyvm.finance.storage.StoredOrder;
import com.imyvm.finance.storage.StoredTrade;
import com.imyvm.finance.storage.StoredPosition;
import com.imyvm.finance.economy.EconomySettlementResult;
import com.imyvm.finance.transaction.StockOperation;
import com.imyvm.finance.transaction.StockTransaction;
import com.imyvm.finance.transaction.StockTransactionState;
import com.imyvm.finance.trading.TradeCalculator;
import com.imyvm.finance.trading.TradeEstimate;
import com.imyvm.finance.trading.TradeSide;
import com.imyvm.finance.trading.TradeValidationException;
import com.imyvm.finance.trading.TradeValidator;
import com.imyvm.finance.trading.StockPositionView;
import com.imyvm.finance.trading.TradingRules;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
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

        var sell = Commands.literal("sell")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("positionId", StringArgumentType.word())
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .executes(MarketCommands::sell)));

        var estimate = Commands.literal("estimate")
            .then(Commands.argument("symbol", StringArgumentType.word())
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .executes(MarketCommands::estimate)));

        var confirm = Commands.literal("confirm")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("symbol", StringArgumentType.word())
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .then(Commands.argument("snapshotId", StringArgumentType.word())
                        .executes(MarketCommands::confirmBuy))));

        var positions = Commands.literal("positions")
            .requires(CommandSourceStack::isPlayer)
            .executes(context -> positions(context, 1L))
            .then(Commands.argument("page", LongArgumentType.longArg(1))
                .executes(context -> positions(context, LongArgumentType.getLong(context, "page"))));

        var history = Commands.literal("history")
            .requires(CommandSourceStack::isPlayer)
            .executes(MarketCommands::history);

        var pendingTransactionId = Commands.argument("transactionId", StringArgumentType.word())
            .suggests(MarketCommands::suggestPendingTransactionIds);
        var pending = Commands.literal("pending")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .executes(MarketCommands::pending)
            .then(Commands.literal("confirm")
                .then(pendingTransactionId.executes(MarketCommands::confirmPending)))
            .then(Commands.literal("release")
                .then(Commands.argument("transactionId", StringArgumentType.word())
                    .suggests(MarketCommands::suggestPendingTransactionIds)
                    .executes(MarketCommands::releasePending)));

        dispatcher.register(Commands.literal("market")
            .then(Commands.literal("list")
                .executes(MarketCommands::list))
            .then(Commands.literal("quote")
                .then(Commands.argument("symbol", StringArgumentType.word())
                    .executes(MarketCommands::quote)))
            .then(buy)
            .then(sell)
            .then(estimate)
            .then(confirm)
            .then(positions)
            .then(history)
            .then(pending));
    }

    private static int positions(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        long page
    ) {
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.positions.storage_unavailable"));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        try {
            long total = ImyvmFinance.TRADING_STORE.positionCount(player.getUUID());
            long pageCount = Math.max(1L, (total + 9L) / 10L);
            if (page > pageCount) {
                context.getSource().sendFailure(Translator.tr("commands.market.positions.page_unavailable", pageCount));
                return 0;
            }
            java.util.List<StoredPosition> positions =
                ImyvmFinance.TRADING_STORE.findPositions(player.getUUID(), 10L, (page - 1L) * 10L);
            context.getSource().sendSuccess(
                () -> Translator.tr("commands.market.positions.header", total, page, pageCount), false);
            for (StoredPosition position : positions) {
                context.getSource().sendSuccess(
                    () -> Translator.tr(
                        "commands.market.positions.item",
                        position.instrument().symbol(),
                        position.remainingUnits() - position.frozenUnits(),
                        position.frozenUnits(),
                        position.state().name(),
                        position.positionId()),
                    false);
            }
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.positions.storage_unavailable"));
            return 0;
        }
    }

    private static int history(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.history.storage_unavailable"));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        try {
            java.util.List<StoredTrade> trades =
                ImyvmFinance.TRADING_STORE.findRecentTrades(player.getUUID(), 10);
            context.getSource().sendSuccess(
                () -> Translator.tr("commands.market.history.header", trades.size()), false);
            for (StoredTrade trade : trades) {
                context.getSource().sendSuccess(
                    () -> Translator.tr(
                        "commands.market.history.item",
                        trade.side().name(),
                        trade.instrument().symbol(),
                        trade.units(),
                        trade.settlementAmount(),
                        trade.state().name(),
                        Instant.ofEpochMilli(trade.createdAtEpochMillis())),
                    false);
            }
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.history.storage_unavailable"));
            return 0;
        }
    }

    private static int pending(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.pending.storage_unavailable"));
            return 0;
        }
        try {
            java.util.List<StoredOrder> orders =
                ImyvmFinance.TRADING_STORE.findPendingManualOrders();
            context.getSource().sendSuccess(
                () -> Translator.tr("commands.market.pending.header", orders.size()), false);
            for (StoredOrder order : orders) {
                context.getSource().sendSuccess(
                    () -> Translator.tr(
                        "commands.market.pending.item",
                        order.playerId(),
                        order.instrument().symbol(),
                        order.units(),
                        order.amount(),
                        order.transactionId(),
                        Instant.ofEpochMilli(order.createdAtEpochMillis())),
                    false);
            }
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.pending.storage_unavailable"));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestPendingTransactionIds(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        if (ImyvmFinance.TRADING_STORE == null)
            return builder.buildFuture();
        try {
            for (StoredOrder order : ImyvmFinance.TRADING_STORE.findPendingManualOrders())
                builder.suggest(order.transactionId().toString());
        } catch (Exception exception) {
            return builder.buildFuture();
        }
        return builder.buildFuture();
    }

    private static int confirmPending(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return resolvePending(context, true);
    }

    private static int releasePending(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return resolvePending(context, false);
    }

    private static int resolvePending(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        boolean confirm) {
        UUID transactionId;
        try {
            transactionId = UUID.fromString(StringArgumentType.getString(context, "transactionId"));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.pending.invalid_transaction"));
            return 0;
        }
        if (ImyvmFinance.TRANSACTION_STORE == null || ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.pending.storage_unavailable"));
            return 0;
        }

        try {
            Optional<StockTransaction> storedTransaction =
                ImyvmFinance.TRANSACTION_STORE.find(transactionId);
            Optional<StoredOrder> storedOrder =
                ImyvmFinance.TRADING_STORE.findOrder(transactionId);
            if (storedTransaction.isEmpty() || storedOrder.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.pending.not_found"));
                return 0;
            }

            StockTransaction transaction = storedTransaction.get();
            StoredOrder order = storedOrder.get();
            if (confirm) {
                if (transaction.state() == StockTransactionState.PENDING_MANUAL) {
                    ImyvmFinance.TRANSACTION_STORE.transition(
                        transactionId,
                        StockTransactionState.FINANCE_CONFIRMED,
                        "manual_confirmed",
                        System.currentTimeMillis());
                } else if (transaction.state() != StockTransactionState.FINANCE_CONFIRMED) {
                    context.getSource().sendFailure(Translator.tr("commands.market.pending.not_pending"));
                    return 0;
                }
                if (order.state() == com.imyvm.finance.trading.StockOrderState.PENDING_MANUAL) {
                    if (transaction.operation() == StockOperation.BUY) {
                        ImyvmFinance.TRADING_STORE.activateBuy(transactionId);
                    } else if (order.positionId() != null) {
                        ImyvmFinance.TRADING_STORE.activateSell(
                            transactionId, order.positionId(), order.units());
                    } else {
                        context.getSource().sendFailure(Translator.tr("commands.market.pending.missing_position"));
                        return 0;
                    }
                }
                context.getSource().sendSuccess(
                    () -> Translator.tr("commands.market.pending.confirmed", transactionId), false);
                return Command.SINGLE_SUCCESS;
            }

            if (transaction.state() == StockTransactionState.PENDING_MANUAL) {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId,
                    StockTransactionState.CANCELLED,
                    "manual_released",
                    System.currentTimeMillis());
            } else if (transaction.state() != StockTransactionState.CANCELLED) {
                context.getSource().sendFailure(Translator.tr("commands.market.pending.not_pending"));
                return 0;
            }
            if (order.state() == com.imyvm.finance.trading.StockOrderState.PENDING_MANUAL) {
                if (transaction.operation() == StockOperation.BUY) {
                    ImyvmFinance.TRADING_STORE.cancel(transactionId);
                } else if (order.positionId() != null) {
                    ImyvmFinance.TRADING_STORE.releaseSell(
                        transactionId, order.positionId(), order.units());
                } else {
                    context.getSource().sendFailure(Translator.tr("commands.market.pending.missing_position"));
                    return 0;
                }
            }
            context.getSource().sendSuccess(
                () -> Translator.tr("commands.market.pending.released", transactionId), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.pending.resolution_failed"));
            return 0;
        }
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
                TradeSide.BUY, storedQuote.get(), units, System.currentTimeMillis(), ImyvmFinance.TRADING_RULES);
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
            String command = "/market confirm " + estimate.instrument().symbol() + " " + estimate.units()
                + " " + estimate.snapshotId();
            MutableComponent confirmation = Translator.tr("commands.market.estimate.confirm").copy()
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));
            context.getSource().sendSuccess(() -> confirmation, false);
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

    private static int sell(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        UUID positionId;
        try {
            positionId = UUID.fromString(StringArgumentType.getString(context, "positionId"));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.sell.invalid_position"));
            return 0;
        }
        long units = LongArgumentType.getLong(context, "units");
        if (ImyvmFinance.QUOTE_STORE == null
            || ImyvmFinance.TRANSACTION_STORE == null
            || ImyvmFinance.TRADING_STORE == null
            || ImyvmFinance.ECONOMY_SETTLEMENT == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.sell.storage_unavailable"));
            return 0;
        }

        long now = System.currentTimeMillis();
        try {
            Optional<StoredPosition> storedPosition = ImyvmFinance.TRADING_STORE.findPosition(positionId);
            if (storedPosition.isEmpty() || !storedPosition.get().playerId().equals(player.getUUID())) {
                context.getSource().sendFailure(Translator.tr("commands.market.sell.invalid_position"));
                return 0;
            }
            StoredPosition position = storedPosition.get();
            Optional<StoredQuote> storedQuote = ImyvmFinance.QUOTE_STORE.findLatest(position.instrument());
            if (storedQuote.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", position.instrument().symbol()));
                return 0;
            }
            TradeEstimate estimate = TradeCalculator.estimate(
                TradeSide.SELL, storedQuote.get(), units, now, ImyvmFinance.TRADING_RULES);
            TradeValidator.validateSell(
                estimate,
                new StockPositionView(
                    position.positionId(),
                    position.playerId(),
                    position.instrument(),
                    position.remainingUnits(),
                    position.frozenUnits(),
                    position.buySnapshotId(),
                    position.boughtAtEpochMillis(),
                    position.earliestSellAtEpochMillis()),
                storedQuote.get().fetchedAtEpochMillis(),
                now,
                dailySellAmount(player.getUUID(), now),
                ImyvmFinance.TRADING_RULES);

            UUID orderId = UUID.randomUUID();
            UUID transactionId = UUID.randomUUID();
            StockTransaction transaction = new StockTransaction(
                transactionId,
                player.getUUID(),
                StockOperation.SELL,
                orderId.toString(),
                position.instrument(),
                estimate.settlementAmount(),
                StockTransactionState.PREPARED,
                null,
                now,
                now);
            ImyvmFinance.TRANSACTION_STORE.createPrepared(transaction);
            try {
                ImyvmFinance.TRADING_STORE.createPendingSell(
                    orderId, UUID.randomUUID(), positionId, transaction, estimate, now);
            } catch (Exception exception) {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId, StockTransactionState.CANCELLED, "finance_prepare_failed", now);
                context.getSource().sendFailure(Translator.tr("commands.market.sell.storage_unavailable"));
                return 0;
            }

            EconomySettlementResult settlement = ImyvmFinance.ECONOMY_SETTLEMENT.settle(player, transaction);
            if (settlement.state() != StockTransactionState.ECONOMY_CONFIRMED) {
                ImyvmFinance.TRADING_STORE.markSellPendingManual(transactionId, positionId);
                context.getSource().sendFailure(Translator.tr("commands.market.sell.pending_manual"));
                return 0;
            }

            try {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId, StockTransactionState.FINANCE_CONFIRMED, "finance_confirmed", System.currentTimeMillis());
                ImyvmFinance.TRADING_STORE.activateSell(transactionId, positionId, estimate.units());
            } catch (Exception exception) {
                try {
                    ImyvmFinance.TRANSACTION_STORE.markPending(
                        transactionId, "finance_activation", exception.getClass().getSimpleName(),
                        null, System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                try {
                    ImyvmFinance.TRADING_STORE.markSellPendingManual(transactionId, positionId);
                } catch (Exception ignored) {
                }
                context.getSource().sendFailure(Translator.tr("commands.market.sell.pending_manual"));
                return 0;
            }

            context.getSource().sendSuccess(
                () -> Translator.tr(
                    "commands.market.sell.success",
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
            context.getSource().sendFailure(Translator.tr("commands.market.sell.storage_unavailable"));
            return 0;
        }
    }

    private static long dailySellAmount(UUID playerId, long nowEpochMillis) throws Exception {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate date = LocalDate.now(zone);
        return ImyvmFinance.TRADING_STORE.dailySellAmount(
            playerId,
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
    }

    private static int confirmBuy(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return buy(context, StringArgumentType.getString(context, "snapshotId"));
    }

    private static int buy(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return buy(context, null);
    }

    private static int buy(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, String snapshotId) {
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
            Optional<StoredQuote> storedQuote = snapshotId == null
                ? ImyvmFinance.QUOTE_STORE.findLatest(instrument)
                : ImyvmFinance.QUOTE_STORE.find(instrument, snapshotId);
            if (storedQuote.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.symbol()));
                return 0;
            }
            TradeEstimate estimate = TradeCalculator.estimate(
                TradeSide.BUY, storedQuote.get(), units, now, ImyvmFinance.TRADING_RULES);
            ZoneId zone = ZoneId.systemDefault();
            LocalDate date = LocalDate.now(zone);
            long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            TradeValidator.validateBuy(
                estimate,
                ImyvmFinance.TRADING_STORE.dailyBuyAmount(player.getUUID(), dayStart, dayEnd),
                ImyvmFinance.TRADING_STORE.positionValue(player.getUUID()),
                ImyvmFinance.TRADING_RULES);

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
                    now + ImyvmFinance.TRADING_RULES.sellCooldownMillis());
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
                    ImyvmFinance.TRANSACTION_STORE.markPending(
                        transactionId, "finance_activation", exception.getClass().getSimpleName(),
                        null, System.currentTimeMillis());
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
