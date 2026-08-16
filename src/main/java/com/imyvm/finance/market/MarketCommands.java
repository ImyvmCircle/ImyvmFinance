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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;

import java.util.function.Supplier;

public final class MarketCommands {
    private static final long CONFIRMATION_TTL_MILLIS = 10L * 60 * 1000;
    private static final Map<UUID, PendingConfirmation> CONFIRMATIONS = new HashMap<>();

    private record PendingConfirmation(
        UUID playerId, TradeSide side, Instrument instrument, UUID positionId, long units, String snapshotId, long createdAt
    ) {
    }

    private MarketCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
        var buy = Commands.literal("buy")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("symbol", StringArgumentType.word())
                .suggests(MarketCommands::suggestInstruments)
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .suggests(MarketCommands::suggestUnits)
                    .executes(MarketCommands::estimate)));

        var sell = Commands.literal("sell")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("positionId", StringArgumentType.word())
                .suggests(MarketCommands::suggestPositions)
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .suggests(MarketCommands::suggestSellUnits)
                    .executes(context -> sell(context, false))));

        var estimate = Commands.literal("estimate")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("symbol", StringArgumentType.word())
                .suggests(MarketCommands::suggestInstruments)
                .then(Commands.argument("units", LongArgumentType.longArg(1))
                    .suggests(MarketCommands::suggestUnits)
                    .executes(MarketCommands::estimate)));

        var confirm = Commands.literal("confirm")
            .requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("confirmationId", StringArgumentType.word())
                .executes(MarketCommands::confirm));

        var tradingSymbol = Commands.argument("symbol", StringArgumentType.word())
            .suggests(MarketCommands::suggestInstruments);
        var trading = Commands.literal("trading")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("status")
                .executes(MarketCommands::tradingStatus)
                .then(tradingSymbol.executes(MarketCommands::tradingInstrumentStatus)))
            .then(Commands.literal("enable")
                .executes(context -> setTrading(context, true, null))
                .then(tradingSymbol.executes(context -> setInstrumentTrading(context, true))))
            .then(Commands.literal("disable")
                .executes(context -> setTrading(context, false, null))
                .then(tradingSymbol.executes(context -> setInstrumentTrading(context, false))));

        var positions = Commands.literal("positions")
            .requires(CommandSourceStack::isPlayer)
            .executes(context -> positions(context, 1L))
            .then(Commands.argument("page", LongArgumentType.longArg(1))
                .executes(context -> positions(context, LongArgumentType.getLong(context, "page"))));

        var history = Commands.literal("history")
            .requires(CommandSourceStack::isPlayer)
            .executes(context -> history(context, 1L))
            .then(Commands.argument("page", LongArgumentType.longArg(1))
                .executes(context -> history(context, LongArgumentType.getLong(context, "page"))));

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

        var market = dispatcher.register(Commands.literal("market")
            .then(Commands.literal("list")
                .executes(MarketCommands::list))
            .then(Commands.literal("quote")
                .then(Commands.argument("symbol", StringArgumentType.word())
                    .suggests(MarketCommands::suggestInstruments)
                    .executes(MarketCommands::quote)))
            .then(buy)
            .then(sell)
            .then(estimate)
            .then(confirm)
            .then(trading)
            .then(positions)
            .then(history)
            .then(pending));
        dispatcher.register(Commands.literal("mkt").redirect(market));
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ImyvmFinance.MOD_ID);

    private static void sendPageFooter(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        String command, long page, long pageCount
    ) {
        if (pageCount <= 1)
            return;
        MutableComponent footer = Component.empty();
        if (page > 1)
            footer.append(Translator.tr("commands.market.page.prev").copy()
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command + " " + (page - 1)))));
        if (page > 1 && page < pageCount)
            footer.append(" ");
        if (page < pageCount)
            footer.append(Translator.tr("commands.market.page.next").copy()
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command + " " + (page + 1)))));
        context.getSource().sendSuccess(() -> footer, false);
    }

    private static int failUnexpected(CommandSourceStack source, String operation, Exception exception) {
        LOGGER.warn("Finance {} failed unexpectedly", operation, exception);
        source.sendFailure(Translator.tr("commands.market.error"));
        return 0;
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
                long availableUnits = position.remainingUnits() - position.frozenUnits();
                String currentValue = "-";
                String profitLoss = "-";
                if (ImyvmFinance.QUOTE_STORE != null) {
                    try {
                        Optional<StoredQuote> storedQuote =
                            ImyvmFinance.QUOTE_STORE.findLatest(position.instrument());
                        if (storedQuote.isPresent()) {
                            long value = BigDecimal.valueOf(storedQuote.get().quote().priceScaled(), 4)
                                .multiply(BigDecimal.valueOf(position.remainingUnits()))
                                .divide(BigDecimal.TEN, 0, RoundingMode.FLOOR)
                                .longValueExact();
                            currentValue = Long.toString(value);
                            profitLoss = Long.toString(value - position.positionValue());
                        }
                    } catch (Exception ignored) {
                    }
                }
                MutableComponent item = Translator.tr(
                    "commands.market.positions.item",
                    instrumentLabel(position.instrument()),
                    availableUnits,
                    position.frozenUnits(),
                    position.positionValue(),
                    currentValue,
                    profitLoss,
                    positionState(position.state())).copy();
                if (availableUnits > 0) {
                    item.append(" ").append(Translator.tr("commands.market.positions.sell").copy()
                        .withStyle(style -> style
                            .withClickEvent(new ClickEvent.SuggestCommand(
                                "/market sell " + position.positionId() + " "))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Translator.tr("commands.market.positions.sell_hint")))));
                    item.append(" ").append(Translator.tr("commands.market.positions.sell_all").copy()
                        .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(
                            "/market sell " + position.positionId() + " " + availableUnits))));
                }
                context.getSource().sendSuccess(() -> item, false);
            }
            sendPageFooter(context, "/market positions", page, pageCount);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "positions", exception);
        }
    }

    private static int history(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        long page
    ) {
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.history.storage_unavailable"));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        try {
            long total = ImyvmFinance.TRADING_STORE.tradeCount(player.getUUID());
            long pageCount = Math.max(1L, (total + 9L) / 10L);
            if (page > pageCount) {
                context.getSource().sendFailure(Translator.tr("commands.market.positions.page_unavailable", pageCount));
                return 0;
            }
            java.util.List<StoredTrade> trades =
                ImyvmFinance.TRADING_STORE.findRecentTrades(player.getUUID(), 10, (page - 1L) * 10L);
            context.getSource().sendSuccess(
                () -> Translator.tr("commands.market.history.header", total, page, pageCount), false);
            for (StoredTrade trade : trades) {
                context.getSource().sendSuccess(
                    () -> Translator.tr(
                        "commands.market.history.item",
                        tradeSide(trade.side()),
                        instrumentLabel(trade.instrument()),
                        trade.units(),
                        trade.settlementAmount(),
                        tradeState(trade.state()),
                        Instant.ofEpochMilli(trade.createdAtEpochMillis())),
                    false);
            }
            sendPageFooter(context, "/market history", page, pageCount);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "history", exception);
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
            return failUnexpected(context.getSource(), "pending list", exception);
        }
    }

    private static CompletableFuture<Suggestions> suggestInstruments(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        for (Instrument instrument : Instrument.values())
            builder.suggest(instrument.symbol());
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPositions(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        if (ImyvmFinance.TRADING_STORE == null || !context.getSource().isPlayer())
            return builder.buildFuture();
        try {
            for (StoredPosition position : ImyvmFinance.TRADING_STORE.findPositions(context.getSource().getPlayer().getUUID())) {
                long availableUnits = position.remainingUnits() - position.frozenUnits();
                builder.suggest(position.positionId().toString(),
                    () -> position.instrument().symbol() + " | " + availableUnits);
            }
        } catch (Exception exception) {
            return builder.buildFuture();
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestUnits(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        builder.suggest(Long.toString(ImyvmFinance.TRADING_RULES.minUnits()));
        builder.suggest("10");
        builder.suggest("100");
        builder.suggest("1000");
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestSellUnits(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        if (ImyvmFinance.TRADING_STORE == null || !context.getSource().isPlayer())
            return builder.buildFuture();
        try {
            UUID positionId = UUID.fromString(StringArgumentType.getString(context, "positionId"));
            Optional<StoredPosition> storedPosition = ImyvmFinance.TRADING_STORE.findPosition(positionId);
            if (storedPosition.isPresent()
                && storedPosition.get().playerId().equals(context.getSource().getPlayer().getUUID())) {
                long availableUnits = storedPosition.get().remainingUnits() - storedPosition.get().frozenUnits();
                if (availableUnits > 0)
                    builder.suggest(Long.toString(availableUnits));
            }
        } catch (Exception exception) {
            return builder.buildFuture();
        }
        return builder.buildFuture();
    }

    private static Instrument instrument(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return Instrument.fromSymbol(StringArgumentType.getString(context, "symbol"));
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

    private static int tradingStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.trading.storage_unavailable"));
            return 0;
        }
        try {
            boolean enabled = ImyvmFinance.TRADING_STORE.isGlobalTradingEnabled();
            context.getSource().sendSuccess(
                () -> Translator.tr(enabled
                    ? "commands.market.trading.global.enabled"
                    : "commands.market.trading.global.disabled"), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "trading control", exception);
        }
    }

    private static int tradingInstrumentStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        Instrument instrument = instrument(context);
        if (instrument == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unknown_symbol"));
            return 0;
        }
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.trading.storage_unavailable"));
            return 0;
        }
        try {
            boolean enabled = ImyvmFinance.TRADING_STORE.isTradingEnabled(instrument);
            context.getSource().sendSuccess(
                () -> Translator.tr(enabled
                    ? "commands.market.trading.instrument.enabled"
                    : "commands.market.trading.instrument.disabled", instrument.symbol()), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "trading control", exception);
        }
    }

    private static int setInstrumentTrading(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, boolean enabled
    ) {
        Instrument instrument = instrument(context);
        if (instrument == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unknown_symbol"));
            return 0;
        }
        return setTrading(context, enabled, instrument);
    }

    private static int setTrading(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, boolean enabled, Instrument instrument
    ) {
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.trading.storage_unavailable"));
            return 0;
        }
        try {
            if (instrument == null) {
                ImyvmFinance.TRADING_STORE.setGlobalTradingEnabled(enabled);
                context.getSource().sendSuccess(() -> Translator.tr(enabled
                    ? "commands.market.trading.global.enabled_by"
                    : "commands.market.trading.global.disabled_by", context.getSource().getTextName()), true);
            } else {
                ImyvmFinance.TRADING_STORE.setTradingEnabled(instrument, enabled);
                context.getSource().sendSuccess(() -> Translator.tr(enabled
                    ? "commands.market.trading.instrument.enabled_by"
                    : "commands.market.trading.instrument.disabled_by", instrument.symbol(), context.getSource().getTextName()), true);
            }
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "trading control", exception);
        }
    }

    private static boolean tradingEnabled(CommandSourceStack source, Instrument instrument) throws Exception {
        if (!ImyvmFinance.TRADING_STORE.isGlobalTradingEnabled()) {
            source.sendFailure(Translator.tr("commands.market.trade.disabled_global"));
            return false;
        }
        if (!ImyvmFinance.TRADING_STORE.isTradingEnabled(instrument)) {
            source.sendFailure(Translator.tr("commands.market.trade.disabled_instrument", instrumentLabel(instrument)));
            return false;
        }
        return true;
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
                if (transaction.state() != StockTransactionState.PENDING_MANUAL
                    && transaction.state() != StockTransactionState.FINANCE_CONFIRMED) {
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
                if (transaction.state() == StockTransactionState.PENDING_MANUAL)
                    ImyvmFinance.TRANSACTION_STORE.transition(
                        transactionId,
                        StockTransactionState.FINANCE_CONFIRMED,
                        "manual_confirmed",
                        System.currentTimeMillis());
                context.getSource().sendSuccess(
                    () -> Translator.tr("commands.market.pending.confirmed", transactionId), false);
                return Command.SINGLE_SUCCESS;
            }

            if (transaction.state() != StockTransactionState.PENDING_MANUAL
                && transaction.state() != StockTransactionState.CANCELLED) {
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
            if (transaction.state() == StockTransactionState.PENDING_MANUAL)
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId,
                    StockTransactionState.CANCELLED,
                    "manual_released",
                    System.currentTimeMillis());
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
            Component item = Translator.tr(
                "commands.market.list.item", instrumentLabel(instrument), instrument.market()).copy()
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(
                    "/market estimate " + instrument.symbol() + " " + ImyvmFinance.TRADING_RULES.minUnits())));
            message.append("\n").append(item);
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
            return failUnexpected(context.getSource(), "quote", exception);
        }

        if (storedQuote.isEmpty()) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.symbol()));
            return 0;
        }

        StoredQuote quote = storedQuote.get();
        context.getSource().sendSuccess(
            () -> Translator.tr(
                "commands.market.quote.result",
                instrumentLabel(quote.quote().instrument()),
                formatPrice(quote.quote().priceScaled()),
                formatPercent(quote.quote().changeBps())),
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
        if (ImyvmFinance.QUOTE_STORE == null || ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.quote.storage_unavailable"));
            return 0;
        }

        try {
            Optional<StoredQuote> storedQuote = ImyvmFinance.QUOTE_STORE.findLatest(instrument);
            if (storedQuote.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.symbol()));
                return 0;
            }
            long now = System.currentTimeMillis();
            TradeEstimate estimate = TradeCalculator.estimate(
                TradeSide.BUY, storedQuote.get(), units, now, ImyvmFinance.TRADING_RULES);
            ServerPlayer player = context.getSource().getPlayer();
            ZoneId zone = ZoneId.systemDefault();
            LocalDate date = LocalDate.now(zone);
            long dailyBuyUsed = ImyvmFinance.TRADING_STORE.dailyBuyAmount(
                player.getUUID(),
                date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            TradeValidator.validateBuy(
                estimate,
                dailyBuyUsed,
                ImyvmFinance.TRADING_STORE.positionValue(player.getUUID()),
                ImyvmFinance.TRADING_RULES);
            long balance = com.imyvm.economy.api.DatabaseApi.getInstance().getPlayer(player).getMoney();
            long dailyBuyRemaining =
                ImyvmFinance.TRADING_RULES.dailyBuyLimit() - dailyBuyUsed - estimate.settlementAmount();
            context.getSource().sendSuccess(
                () -> Translator.tr(
                    "commands.market.estimate.result",
                    instrumentLabel(estimate.instrument()),
                    estimate.units(),
                    formatPrice(estimate.executionPriceScaled()),
                    estimate.feeAmount(),
                    estimate.settlementAmount(),
                    estimate.slippageBps(),
                    balance,
                    balance - estimate.settlementAmount(),
                    dailyBuyRemaining),
                false);
            String command = "/market confirm " + createConfirmation(
                player.getUUID(), TradeSide.BUY, estimate.instrument(), null,
                estimate.units(), estimate.snapshotId());
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
            return failUnexpected(context.getSource(), "estimate", exception);
        }
    }

    private static int confirm(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        UUID confirmationId;
        try {
            confirmationId = UUID.fromString(StringArgumentType.getString(context, "confirmationId"));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.confirmation.expired"));
            return 0;
        }
        PendingConfirmation confirmation = CONFIRMATIONS.get(confirmationId);
        ServerPlayer player = context.getSource().getPlayer();
        if (confirmation == null || !confirmation.playerId().equals(player.getUUID())
            || System.currentTimeMillis() - confirmation.createdAt() > CONFIRMATION_TTL_MILLIS) {
            context.getSource().sendFailure(Translator.tr("commands.market.confirmation.expired"));
            return 0;
        }
        CONFIRMATIONS.remove(confirmationId);
        return confirmation.side() == TradeSide.BUY
            ? buy(context, confirmation.instrument(), confirmation.units(), confirmation.snapshotId())
            : sell(context, confirmation.positionId(), confirmation.units(), confirmation.snapshotId(), true);
    }

    private static UUID createConfirmation(UUID playerId, TradeSide side, Instrument instrument,
                                           UUID positionId, long units, String snapshotId) {
        long now = System.currentTimeMillis();
        CONFIRMATIONS.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > CONFIRMATION_TTL_MILLIS
            || entry.getValue().playerId().equals(playerId));
        UUID confirmationId = UUID.randomUUID();
        CONFIRMATIONS.put(confirmationId,
            new PendingConfirmation(playerId, side, instrument, positionId, units, snapshotId, now));
        return confirmationId;
    }

    private static int sell(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, boolean confirmed) {
        try {
            return sell(context, UUID.fromString(StringArgumentType.getString(context, "positionId")),
                LongArgumentType.getLong(context, "units"), null, confirmed);
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.sell.invalid_position"));
            return 0;
        }
    }

    private static int sell(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        UUID positionId, long units, String snapshotId, boolean confirmed
    ) {
        ServerPlayer player = context.getSource().getPlayer();
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
            if (!tradingEnabled(context.getSource(), position.instrument()))
                return 0;
            Optional<StoredQuote> storedQuote = snapshotId == null
                ? ImyvmFinance.QUOTE_STORE.findLatest(position.instrument())
                : ImyvmFinance.QUOTE_STORE.find(position.instrument(), snapshotId);
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
            long soldCostBasis = BigDecimal.valueOf(position.positionValue())
                .multiply(BigDecimal.valueOf(estimate.units()))
                .divide(BigDecimal.valueOf(position.remainingUnits()), 0, RoundingMode.FLOOR)
                .longValueExact();
            long soldProfitLoss = estimate.settlementAmount() - soldCostBasis;

            if (!confirmed) {
                String command = "/market confirm " + createConfirmation(
                    player.getUUID(), TradeSide.SELL, estimate.instrument(), positionId,
                    estimate.units(), estimate.snapshotId());
                MutableComponent confirmation = Translator.tr("commands.market.sell.estimate.confirm").copy()
                    .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));
                context.getSource().sendSuccess(() -> Translator.tr(
                    "commands.market.sell.estimate.result",
                    instrumentLabel(estimate.instrument()), estimate.units(), estimate.settlementAmount(),
                    formatPrice(estimate.executionPriceScaled()), estimate.feeAmount(), soldProfitLoss), false);
                context.getSource().sendSuccess(() -> confirmation, false);
                return Command.SINGLE_SUCCESS;
            }

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
            try {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId, StockTransactionState.FINANCE_CONFIRMED, "finance_confirmed", System.currentTimeMillis());
            } catch (Exception exception) {
                context.getSource().sendFailure(Translator.tr("commands.market.sell.pending_manual"));
                return 0;
            }

            context.getSource().sendSuccess(
                () -> Translator.tr(
                    "commands.market.sell.success",
                    instrumentLabel(estimate.instrument()),
                    estimate.units(),
                    estimate.settlementAmount(),
                    formatPrice(estimate.executionPriceScaled()),
                    estimate.feeAmount(),
                    soldProfitLoss),
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
            return failUnexpected(context.getSource(), "sell", exception);
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

    private static int buy(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        Instrument instrument, long units, String snapshotId
    ) {
        ServerPlayer player = context.getSource().getPlayer();
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
            if (!tradingEnabled(context.getSource(), instrument))
                return 0;
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
            try {
                ImyvmFinance.TRANSACTION_STORE.transition(
                    transactionId, StockTransactionState.FINANCE_CONFIRMED, "finance_confirmed", System.currentTimeMillis());
            } catch (Exception exception) {
                context.getSource().sendFailure(Translator.tr("commands.market.buy.pending_manual"));
                return 0;
            }

            context.getSource().sendSuccess(
                () -> Translator.tr(
                    "commands.market.buy.success",
                    instrumentLabel(estimate.instrument()),
                    estimate.units(),
                    estimate.settlementAmount(),
                    formatPrice(estimate.executionPriceScaled()),
                    estimate.feeAmount()),
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
            return failUnexpected(context.getSource(), "buy", exception);
        }
    }

    public static Component instrumentLabel(Instrument instrument) {
        return Translator.tr("commands.market.instrument.label",
            Translator.tr("instrument." + instrument.name().toLowerCase()), instrument.symbol());
    }

    private static Component positionState(com.imyvm.finance.trading.StockOrderState state) {
        return Translator.tr("commands.market.state.position." + state.name().toLowerCase());
    }

    private static Component tradeState(com.imyvm.finance.trading.StockTradeState state) {
        return Translator.tr("commands.market.state.trade." + state.name().toLowerCase());
    }

    private static Component tradeSide(TradeSide side) {
        return Translator.tr("commands.market.side." + side.name().toLowerCase());
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
