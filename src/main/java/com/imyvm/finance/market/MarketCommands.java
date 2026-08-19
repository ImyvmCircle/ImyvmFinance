package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import com.imyvm.finance.ImyvmFinance;
import com.imyvm.finance.storage.StoredQuote;
import com.imyvm.finance.storage.StoredOrder;
import com.imyvm.finance.storage.StoredTrade;
import com.imyvm.finance.storage.StoredPosition;
import com.imyvm.finance.economy.EconomySettlementResult;
import com.imyvm.finance.economy.StockEconomySettlement;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
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
import net.minecraft.world.entity.player.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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

        var sourceMarket = Commands.argument("market", StringArgumentType.word());
        var sourceProvider = Commands.argument("provider", StringArgumentType.word());
        var sourceControl = Commands.literal("source")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("status").executes(MarketCommands::sourceStatus))
            .then(Commands.literal("enable")
                .then(sourceMarket.then(sourceProvider.executes(context -> setSource(context, true)))))
            .then(Commands.literal("disable")
                .then(sourceMarket.then(sourceProvider.executes(context -> setSource(context, false)))));

        var marketName = Commands.argument("market", StringArgumentType.word());
        var marketControl = Commands.literal("market")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("status")
                .executes(MarketCommands::marketStatus)
                .then(marketName.executes(MarketCommands::marketStatus)))
            .then(Commands.literal("enable")
                .then(marketName.executes(context -> setMarket(context, true))))
            .then(Commands.literal("disable")
                .then(marketName.executes(context -> setMarket(context, false))));

        var adminPlayer = Commands.argument("player", StringArgumentType.word());
        var adminSymbol = Commands.argument("symbol", StringArgumentType.word())
            .suggests(MarketCommands::suggestInstruments);
        var adminSnapshot = Commands.argument("snapshotId", StringArgumentType.word());
        var adminUnits = Commands.argument("units", LongArgumentType.longArg(1));
        var admin = Commands.literal("admin")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("position")
                .then(adminPlayer.then(adminSymbol.then(adminSnapshot.executes(MarketCommands::adminPosition)))))
            .then(Commands.literal("settle")
                .then(adminPlayer.then(adminSymbol.then(adminUnits.then(adminSnapshot.executes(MarketCommands::adminSettle))))));

        var rules = Commands.literal("rules")
            .requires(CommandSourceStack::isPlayer)
            .executes(MarketCommands::rules);

        var briefing = Commands.literal("briefing")
            .requires(CommandSourceStack::isPlayer)
            .executes(MarketCommands::briefing);

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

        var help = Commands.literal("help")
            .executes(MarketCommands::help);

        var setup = Commands.literal("setup")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .executes(MarketCommands::setup)
            .then(Commands.literal("poll")
                .then(Commands.argument("interval", LongArgumentType.longArg(1))
                    .then(Commands.argument("delay", LongArgumentType.longArg(0))
                        .then(Commands.argument("jitter", LongArgumentType.longArg(0))
                            .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(MarketCommands::configurePoll))))))
            .then(Commands.literal("briefing")
                .then(Commands.argument("interval", LongArgumentType.longArg(1))
                    .then(Commands.argument("delay", LongArgumentType.longArg(0))
                        .then(Commands.argument("enabled", StringArgumentType.word())
                            .executes(MarketCommands::configureBriefing)))))
            .then(Commands.literal("providers")
                .then(Commands.argument("market", StringArgumentType.word())
                    .then(Commands.argument("order", StringArgumentType.greedyString())
                        .executes(MarketCommands::configureProviders))))
            .then(Commands.literal("holiday")
                .then(Commands.argument("market", StringArgumentType.word())
                    .then(Commands.argument("dates", StringArgumentType.greedyString())
                        .executes(MarketCommands::configureHoliday))));

        var market = dispatcher.register(Commands.literal("imyvm-market")
            .executes(MarketCommands::help)
            .then(help)
            .then(setup)
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
            .then(sourceControl)
            .then(marketControl)
            .then(admin)
            .then(rules)
            .then(briefing)
            .then(positions)
            .then(history)
            .then(pending));
        dispatcher.register(Commands.literal("mkt").redirect(market));
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ImyvmFinance.MOD_ID);

    private static int help(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        sendPlayerDisclaimer(source);
        source.sendSuccess(() -> Translator.tr("commands.market.help.header"), false);
        source.sendSuccess(() -> Translator.tr("commands.market.help.intro"), false);
        source.sendSuccess(() -> Translator.tr("commands.market.help.symbol_hint"), false);
        for (String entry : new String[]{
            "list", "quote", "buy", "estimate", "sell", "positions", "history", "rules", "briefing", "setup", "help"
        })
            source.sendSuccess(() -> Translator.tr("commands.market.help.command." + entry), false);
        if (source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            source.sendSuccess(() -> Translator.tr("commands.market.help.admin.header"), false);
            source.sendSuccess(() -> Translator.tr("commands.market.help.command.source"), false);
            source.sendSuccess(() -> Translator.tr("commands.market.help.command.trading"), false);
            source.sendSuccess(() -> Translator.tr("commands.market.help.command.pending"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setup(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (ImyvmFinance.CONFIG.setupInitialized()) {
            source.sendFailure(Translator.tr("commands.market.setup.already"));
            return 0;
        }
        if (ImyvmFinance.setupCheckInProgress()) {
            source.sendFailure(Translator.tr("commands.market.setup.in_progress"));
            return 0;
        }
        source.sendSuccess(() -> Translator.tr("commands.market.setup.checking"), false);
        ImyvmFinance.checkMarketData().thenAccept(snapshot ->
            source.getServer().execute(() -> {
                try {
                    ImyvmFinance.completeSetup(snapshot);
                    source.sendSuccess(() -> Translator.tr("commands.market.setup.success", snapshot.quotes().size()), true);
                } catch (Exception exception) {
                    source.sendFailure(Translator.tr("commands.market.setup.failed", exception.getMessage()));
                }
            })).exceptionally(error -> {
                source.getServer().execute(() -> source.sendFailure(Translator.tr(
                    "commands.market.setup.failed", error.getCause() == null ? error.getMessage() : error.getCause().getMessage())));
                return null;
            });
        return Command.SINGLE_SUCCESS;
    }

    private static int configurePoll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            ImyvmFinance.configureQuoteSettings(LongArgumentType.getLong(context, "interval"), LongArgumentType.getLong(context, "delay"), LongArgumentType.getLong(context, "jitter"), LongArgumentType.getLong(context, "seed"), ImyvmFinance.CONFIG.briefingIntervalMinutes(), ImyvmFinance.CONFIG.briefingDelaySeconds(), ImyvmFinance.CONFIG.briefingEnabled());
            context.getSource().sendSuccess(() -> Component.literal("quote settings saved; restart required"), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) { return failUnexpected(context.getSource(), "quote configuration", exception); }
    }

    private static int configureBriefing(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            ImyvmFinance.configureQuoteSettings(ImyvmFinance.CONFIG.quotePollIntervalMinutes(), ImyvmFinance.CONFIG.quotePollDelaySeconds(), ImyvmFinance.CONFIG.quoteJitterSeconds(), ImyvmFinance.CONFIG.quoteRandomSeed(), LongArgumentType.getLong(context, "interval"), LongArgumentType.getLong(context, "delay"), Boolean.parseBoolean(StringArgumentType.getString(context, "enabled")));
            context.getSource().sendSuccess(() -> Component.literal("briefing settings saved; restart required"), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) { return failUnexpected(context.getSource(), "briefing configuration", exception); }
    }

    private static int configureProviders(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        String market = StringArgumentType.getString(context, "market").toUpperCase();
        if (!knownMarket(market)) return 0;
        try { ImyvmFinance.configureProviderOrder(market, StringArgumentType.getString(context, "order")); context.getSource().sendSuccess(() -> Component.literal("provider order saved; restart required"), true); return Command.SINGLE_SUCCESS; }
        catch (Exception exception) { return failUnexpected(context.getSource(), "provider configuration", exception); }
    }

    private static int configureHoliday(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        String market = StringArgumentType.getString(context, "market").toUpperCase();
        if (!knownMarket(market)) return 0;
        try { ImyvmFinance.configureHolidays(market, StringArgumentType.getString(context, "dates")); context.getSource().sendSuccess(() -> Component.literal("holiday settings saved; restart required"), true); return Command.SINGLE_SUCCESS; }
        catch (Exception exception) { return failUnexpected(context.getSource(), "holiday configuration", exception); }
    }

    private static int rules(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
        TradingRules rules = ImyvmFinance.TRADING_RULES;
        context.getSource().sendSuccess(() -> Translator.tr("commands.market.rules.header"), false);
        context.getSource().sendSuccess(
            () -> Translator.tr("commands.market.rules.pricing", rules.minUnits()), false);
        context.getSource().sendSuccess(
            () -> Translator.tr("commands.market.rules.fees", rules.feeBps(), rules.baseSlippageBps()), false);
        context.getSource().sendSuccess(
            () -> Translator.tr("commands.market.rules.limits",
                rules.dailyBuyLimit(), rules.dailySellLimit(), rules.positionValueLimit()), false);
        context.getSource().sendSuccess(
            () -> Translator.tr("commands.market.rules.timing",
                rules.sellCooldownMillis() / 60000, rules.maxQuoteAgeMillis() / 60000), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int briefing(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
        if (ImyvmFinance.TRADING_STORE == null) {
            context.getSource().sendFailure(Translator.tr("commands.market.trading.storage_unavailable"));
            return 0;
        }
        UUID playerId = context.getSource().getPlayer().getUUID();
        try {
            boolean optedOut = !ImyvmFinance.TRADING_STORE.isBriefingOptedOut(playerId);
            ImyvmFinance.TRADING_STORE.setBriefingOptedOut(playerId, optedOut);
            context.getSource().sendSuccess(
                () -> Translator.tr(optedOut
                    ? "commands.market.briefing.subscription.off"
                    : "commands.market.briefing.subscription.on"), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "briefing toggle", exception);
        }
    }

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

    private static void sendPlayerDisclaimer(CommandSourceStack source) {
        if (source.isPlayer())
            source.sendSuccess(() -> Translator.tr("commands.market.disclaimer"), false);
    }

    private static boolean requireInitialized(CommandSourceStack source) {
        if (ImyvmFinance.CONFIG.setupInitialized())
            return true;
        source.sendFailure(Translator.tr("commands.market.setup.required"));
        return false;
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
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
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
                                "/imyvm-market sell " + position.positionId() + " "))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Translator.tr("commands.market.positions.sell_hint")))));
                    item.append(" ").append(Translator.tr("commands.market.positions.sell_all").copy()
                        .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(
                            "/imyvm-market sell " + position.positionId() + " " + availableUnits))));
                }
                context.getSource().sendSuccess(() -> item, false);
            }
            sendPageFooter(context, "/imyvm-market positions", page, pageCount);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "positions", exception);
        }
    }

    private static int history(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        long page
    ) {
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
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
            sendPageFooter(context, "/imyvm-market history", page, pageCount);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(context.getSource(), "history", exception);
        }
    }

    private static int pending(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
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
        String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
        for (Instrument instrument : Instrument.values()) {
            if (instrument.commandForm().toLowerCase(java.util.Locale.ROOT).startsWith(remaining))
                builder.suggest(instrument.commandForm(),
                    Translator.tr("commands.market.instrument.label",
                        Translator.tr("instrument." + instrument.name().toLowerCase(java.util.Locale.ROOT)),
                        instrument.symbol()));
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPositions(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        if (!ImyvmFinance.CONFIG.setupInitialized()
            || ImyvmFinance.TRADING_STORE == null || !context.getSource().isPlayer())
            return builder.buildFuture();
        try {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (StoredPosition position : ImyvmFinance.TRADING_STORE.findPositions(context.getSource().getPlayer().getUUID())) {
                long availableUnits = position.remainingUnits() - position.frozenUnits();
                String positionId = position.positionId().toString();
                if (positionId.startsWith(remaining))
                    builder.suggest(positionId,
                        Translator.tr("commands.market.suggest.position",
                            position.instrument().symbol(), availableUnits));
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
        String remaining = builder.getRemaining();
        for (String units : new String[]{
            Long.toString(ImyvmFinance.TRADING_RULES.minUnits()), "10", "100", "1000"
        }) {
            if (units.startsWith(remaining))
                builder.suggest(units, Translator.tr("commands.market.suggest.units"));
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestSellUnits(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        if (!ImyvmFinance.CONFIG.setupInitialized()
            || ImyvmFinance.TRADING_STORE == null || !context.getSource().isPlayer())
            return builder.buildFuture();
        try {
            UUID positionId = UUID.fromString(StringArgumentType.getString(context, "positionId"));
            Optional<StoredPosition> storedPosition = ImyvmFinance.TRADING_STORE.findPosition(positionId);
            if (storedPosition.isPresent()
                && storedPosition.get().playerId().equals(context.getSource().getPlayer().getUUID())) {
                long availableUnits = storedPosition.get().remainingUnits() - storedPosition.get().frozenUnits();
                String suggestion = Long.toString(availableUnits);
                if (availableUnits > 0 && suggestion.startsWith(builder.getRemaining()))
                    builder.suggest(suggestion,
                        Translator.tr("commands.market.suggest.sell_units", availableUnits));
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
        if (!ImyvmFinance.CONFIG.setupInitialized() || ImyvmFinance.TRADING_STORE == null)
            return builder.buildFuture();
        try {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (StoredOrder order : ImyvmFinance.TRADING_STORE.findPendingManualOrders()) {
                String transactionId = order.transactionId().toString();
                if (transactionId.startsWith(remaining))
                    builder.suggest(transactionId,
                        Translator.tr("commands.market.suggest.transaction",
                            order.instrument().symbol(), order.units(), order.amount()));
            }
        } catch (Exception exception) {
            return builder.buildFuture();
        }
        return builder.buildFuture();
    }

    private static int adminPosition(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        try {
            GameProfile profile = resolveAdminProfile(context).orElseThrow();
            Instrument instrument = Instrument.fromSymbol(StringArgumentType.getString(context, "symbol"));
            String snapshotId = StringArgumentType.getString(context, "snapshotId");
            if (instrument == null || ImyvmFinance.QUOTE_STORE == null || ImyvmFinance.TRADING_STORE == null)
                throw new IllegalArgumentException();
            StoredQuote quote = ImyvmFinance.QUOTE_STORE.find(instrument, snapshotId).orElseThrow();
            var positions = ImyvmFinance.TRADING_STORE.findPositions(profile.id()).stream()
                .filter(position -> position.instrument() == instrument)
                .toList();
            long available = positions.stream()
                .mapToLong(position -> position.remainingUnits() - position.frozenUnits())
                .sum();
            long frozen = positions.stream().mapToLong(StoredPosition::frozenUnits).sum();
            long value = forceGrossAmount(quote, available);
            context.getSource().sendSuccess(() -> Translator.tr("commands.market.admin.position",
                profile.name(), instrumentLabel(instrument), available, frozen,
                snapshotId, formatPrice(quote.quote().priceScaled()), value), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            context.getSource().sendFailure(Translator.tr("commands.market.admin.invalid"));
            return 0;
        }
    }

    private static int adminSettle(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        CommandSourceStack source = context.getSource();
        try {
            GameProfile profile = resolveAdminProfile(context).orElseThrow();
            Instrument instrument = Instrument.fromSymbol(StringArgumentType.getString(context, "symbol"));
            long units = LongArgumentType.getLong(context, "units");
            String snapshotId = StringArgumentType.getString(context, "snapshotId");
            if (instrument == null || ImyvmFinance.QUOTE_STORE == null
                || ImyvmFinance.TRANSACTION_STORE == null || ImyvmFinance.TRADING_STORE == null
                || ImyvmFinance.ECONOMY_SETTLEMENT == null)
                throw new IllegalArgumentException();
            StoredQuote quote = ImyvmFinance.QUOTE_STORE.find(instrument, snapshotId).orElseThrow();
            var positions = ImyvmFinance.TRADING_STORE.findPositions(profile.id()).stream()
                .filter(position -> position.instrument() == instrument
                    && position.remainingUnits() - position.frozenUnits() > 0)
                .toList();
            long available = positions.stream()
                .mapToLong(position -> position.remainingUnits() - position.frozenUnits()).sum();
            if (units > available)
                throw new IllegalArgumentException();

            Player proxy = StockEconomySettlement.offlinePlayer(
                source.getServer().overworld(), profile);
            long settledUnits = 0;
            long settledAmount = 0;
            for (StoredPosition position : positions) {
                if (settledUnits == units)
                    break;
                long part = Math.min(units - settledUnits,
                    position.remainingUnits() - position.frozenUnits());
                TradeEstimate estimate = forceEstimate(quote, part);
                UUID orderId = UUID.randomUUID();
                UUID transactionId = UUID.randomUUID();
                StockTransaction transaction = new StockTransaction(
                    transactionId, profile.id(), StockOperation.SELL,
                    "admin_force:" + snapshotId + ":" + orderId, instrument,
                    estimate.settlementAmount(), StockTransactionState.PREPARED, null,
                    System.currentTimeMillis(), System.currentTimeMillis());
                ImyvmFinance.TRANSACTION_STORE.createPrepared(transaction);
                try {
                    ImyvmFinance.TRADING_STORE.createPendingSell(
                        orderId, UUID.randomUUID(), position.positionId(), transaction, estimate,
                        System.currentTimeMillis());
                } catch (Exception exception) {
                    ImyvmFinance.TRANSACTION_STORE.transition(transactionId,
                        StockTransactionState.CANCELLED, "admin_force_prepare_failed", System.currentTimeMillis());
                    throw exception;
                }
                EconomySettlementResult settlement = ImyvmFinance.ECONOMY_SETTLEMENT.settle(proxy, transaction);
                if (settlement.state() != StockTransactionState.ECONOMY_CONFIRMED) {
                    ImyvmFinance.TRADING_STORE.markSellPendingManual(transactionId, position.positionId());
                    break;
                }
                try {
                    ImyvmFinance.TRADING_STORE.activateSell(transactionId, position.positionId(), part);
                    ImyvmFinance.TRANSACTION_STORE.transition(
                        transactionId, StockTransactionState.FINANCE_CONFIRMED,
                        "admin_force_settled", System.currentTimeMillis());
                } catch (Exception exception) {
                    try {
                        ImyvmFinance.TRADING_STORE.markSellPendingManual(transactionId, position.positionId());
                    } catch (Exception ignored) {
                    }
                    break;
                }
                settledUnits += part;
                settledAmount += estimate.settlementAmount();
            }
            if (settledUnits > 0) {
                try {
                    com.imyvm.economy.EconomyMod.data.save();
                } catch (Exception exception) {
                    LOGGER.error("Failed to persist forced settlement balance", exception);
                }
            }
            if (settledUnits != units) {
                source.sendFailure(Translator.tr("commands.market.admin.partial", settledUnits));
                return 0;
            }
            long finalSettledUnits = settledUnits;
            long finalSettledAmount = settledAmount;
            source.sendSuccess(() -> Translator.tr("commands.market.admin.settled",
                profile.name(), instrumentLabel(instrument), finalSettledUnits, finalSettledAmount, snapshotId), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            source.sendFailure(Translator.tr("commands.market.admin.invalid"));
            return 0;
        }
    }

    private static Optional<GameProfile> resolveAdminProfile(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context
    ) {
        String name = StringArgumentType.getString(context, "player");
        var server = context.getSource().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null)
            return Optional.of(online.getGameProfile());
        try {
            return Optional.of(new GameProfile(UUID.fromString(name), name));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static TradeEstimate forceEstimate(StoredQuote quote, long units) {
        long amount = forceGrossAmount(quote, units);
        if (amount <= 0)
            throw new IllegalArgumentException();
        return new TradeEstimate(TradeSide.SELL, quote.quote().instrument(), units,
            quote.snapshotId(), quote.quote().priceScaled(), amount, 0, amount, 0, 0);
    }

    private static long forceGrossAmount(StoredQuote quote, long units) {
        return BigDecimal.valueOf(quote.quote().priceScaled(), 4)
            .multiply(BigDecimal.valueOf(units))
            .divide(BigDecimal.TEN, 0, RoundingMode.FLOOR)
            .longValueExact();
    }

    private static int marketStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        CommandSourceStack source = context.getSource();
        if (!context.getNodes().stream().anyMatch(node -> node.getNode() instanceof ArgumentCommandNode<?, ?>
            && "market".equals(node.getNode().getName())) ) {
            try {
                for (String market : new String[]{"CN", "CRYPTO"}) {
                    boolean enabled = ImyvmFinance.TRADING_STORE != null
                        && ImyvmFinance.TRADING_STORE.isMarketTradingEnabled(market);
                    source.sendSuccess(() -> Translator.tr("commands.market.market.status", marketLabel(market),
                        enabled ? "ENABLED" : "DISABLED"), false);
                }
                return Command.SINGLE_SUCCESS;
            } catch (Exception exception) {
                return failUnexpected(source, "market control", exception);
            }
        }
        String market = StringArgumentType.getString(context, "market").toUpperCase();
        if (!knownMarket(market)) {
            source.sendFailure(Translator.tr("commands.market.error"));
            return 0;
        }
        try {
            boolean enabled = ImyvmFinance.TRADING_STORE != null
                && ImyvmFinance.TRADING_STORE.isMarketTradingEnabled(market);
            source.sendSuccess(() -> Translator.tr("commands.market.market.status", marketLabel(market), enabled ? "ENABLED" : "DISABLED"), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            return failUnexpected(source, "market control", exception);
        }
    }

    private static int setMarket(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, boolean enabled
    ) {
        if (!requireInitialized(context.getSource()))
            return 0;
        CommandSourceStack source = context.getSource();
        String market = StringArgumentType.getString(context, "market").toUpperCase();
        if (!knownMarket(market)) {
            source.sendFailure(Translator.tr("commands.market.error"));
            return 0;
        }
        String path = "/control/market?market=" + market + "&enabled=" + enabled;
        ImyvmFinance.controlMarketData(path).thenAccept(ignored -> source.getServer().execute(() -> {
            try {
                if (ImyvmFinance.TRADING_STORE == null)
                    throw new IllegalStateException("trading storage unavailable");
                ImyvmFinance.TRADING_STORE.setMarketTradingEnabled(market, enabled);
                source.sendSuccess(() -> Translator.tr("commands.market.market." + (enabled ? "enabled" : "disabled"), marketLabel(market)), true);
            } catch (Exception exception) {
                source.sendFailure(Translator.tr("commands.market.control.failed"));
            }
        })).exceptionally(error -> {
            source.getServer().execute(() -> source.sendFailure(Translator.tr("commands.market.control.failed")));
            return null;
        });
        source.sendSuccess(() -> Translator.tr("commands.market.control.requested"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static Component marketLabel(String market) {
        return Translator.tr("commands.market.market.name." + market.toLowerCase());
    }

    private static boolean knownMarket(String market) {
        for (Instrument instrument : Instrument.values())
            if (instrument.market().equals(market))
                return true;
        return false;
    }

    private static int sourceStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        CommandSourceStack source = context.getSource();
        ImyvmFinance.inspectMarketData("/control/status").thenAccept(body ->
            source.getServer().execute(() -> renderSourceStatus(source, body)))
            .exceptionally(error -> {
                source.getServer().execute(() -> source.sendFailure(Translator.tr("commands.market.control.failed")));
                return null;
            });
        return Command.SINGLE_SUCCESS;
    }

    private static void renderSourceStatus(CommandSourceStack source, String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            source.sendSuccess(() -> Translator.tr("commands.market.source.status.header"), false);
            source.sendSuccess(() -> Translator.tr("commands.market.source.status.since", value(root, "statsSince", "-")), false);
            String outages = root.has("marketOutages") ? root.get("marketOutages").toString().replace("\"", "").replace("[", "").replace("]", "") : "";
            source.sendSuccess(() -> Translator.tr("commands.market.source.status.outage", outages.isEmpty() ? "-" : outages), false);
            JsonObject scheduler = root.getAsJsonObject("scheduler");
            if (scheduler != null) {
                source.sendSuccess(() -> Translator.tr("commands.market.source.status.scheduler.config",
                    number(scheduler, "pollIntervalMinutes"), number(scheduler, "pollDelaySeconds"),
                    number(scheduler, "jitterSeconds"), number(scheduler, "randomSeed")), false);
                source.sendSuccess(() -> Translator.tr("commands.market.source.status.scheduler.schedule",
                    value(scheduler, "lastNominalPollAt", "-"), number(scheduler, "lastJitterSeconds"),
                    value(scheduler, "lastScheduledPollAt", "-"), value(scheduler, "nextNominalPollAt", "-")), false);
                source.sendSuccess(() -> Translator.tr("commands.market.source.status.scheduler.refresh",
                    value(scheduler, "lastRefreshStartedAt", "-"), value(scheduler, "lastRefreshCompletedAt", "-"),
                    value(scheduler, "lastRefreshStatus", "-"), value(scheduler, "lastSnapshotId", "-"),
                    value(scheduler, "lastRefreshError", "-")), false);
            }
            JsonObject announcements = root.getAsJsonObject("announcements");
            if (announcements != null) {
                source.sendSuccess(() -> Translator.tr("commands.market.source.status.announcement.startup",
                    value(announcements, "startupAnnouncementSentAt", "-")), false);
                source.sendSuccess(() -> Translator.tr("commands.market.source.status.announcement.briefing",
                    value(announcements, "lastBriefingSentAt", "-"), value(announcements, "nextBriefingAt", "-"),
                    value(announcements, "lastBriefingSnapshotId", "-")), false);
                JsonObject markets = announcements.getAsJsonObject("markets");
                if (markets != null) {
                    for (String market : new String[]{"CN", "CRYPTO"}) {
                        JsonObject item = markets.getAsJsonObject(market);
                        if (item == null) continue;
                        source.sendSuccess(() -> Translator.tr("commands.market.source.status.announcement.market",
                            marketLabel(market), value(item, "status", "UNKNOWN"), value(item, "lastEventAt", "-")), false);
                    }
                }
            }
            JsonObject orders = root.getAsJsonObject("providerOrder");
            JsonObject active = root.getAsJsonObject("lastSuccessfulProviders");
            if (orders != null) {
                for (String market : new String[]{"CN", "CRYPTO"}) {
                    if (!orders.has(market)) continue;
                    String order = orders.getAsJsonArray(market).toString().replace("\"", "").replace("[", "").replace("]", "");
                    source.sendSuccess(() -> Translator.tr("commands.market.source.status.market",
                        marketLabel(market), value(active, market, "-"), order), false);
                }
            }
            JsonObject stats = root.getAsJsonObject("providerStats");
            if (stats != null) {
                for (String key : stats.keySet()) {
                    JsonObject item = stats.getAsJsonObject(key);
                    long requests = number(item, "requests");
                    long failures = number(item, "failures");
                    Component state = number(item, "backoffSecondsRemaining") > 0
                        ? Translator.tr("commands.market.source.status.backoff", number(item, "backoffSecondsRemaining"))
                        : Translator.tr("commands.market.source.status.ready");
                    source.sendSuccess(() -> Translator.tr("commands.market.source.status.provider",
                        key, requests, failures, value(item, "failureRatePercent", "0.00"), state,
                        value(item, "lastAttemptAt", "-"), value(item, "lastSuccessAt", "-"),
                        value(item, "lastFailureAt", "-")), false);
                }
            }
        } catch (Exception exception) {
            source.sendFailure(Translator.tr("commands.market.control.failed"));
        }
    }

    private static String value(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString();
    }

    private static long number(JsonObject object, String key) {
        try { return object == null ? 0 : object.get(key).getAsLong(); }
        catch (Exception ignored) { return 0; }
    }

    private static int setSource(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, boolean enabled
    ) {
        if (!requireInitialized(context.getSource()))
            return 0;
        CommandSourceStack source = context.getSource();
        String market = StringArgumentType.getString(context, "market").toUpperCase();
        String provider = StringArgumentType.getString(context, "provider").toLowerCase();
        String path = "/control/provider?market=" + market + "&provider=" + provider + "&enabled=" + enabled;
        ImyvmFinance.controlMarketData(path).thenAccept(ignored -> source.getServer().execute(() ->
            source.sendSuccess(() -> Translator.tr("commands.market.source." + (enabled ? "enabled" : "disabled"), market, provider), true)))
            .exceptionally(error -> {
                source.getServer().execute(() -> source.sendFailure(Translator.tr("commands.market.control.failed")));
                return null;
            });
        source.sendSuccess(() -> Translator.tr("commands.market.control.requested"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int tradingStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
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
        if (!requireInitialized(context.getSource()))
            return 0;
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
        if (!requireInitialized(context.getSource()))
            return 0;
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
        if (!requireInitialized(context.getSource()))
            return 0;
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
        if (!requireInitialized(context.getSource()))
            return 0;
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
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
        context.getSource().sendSuccess(() -> Translator.tr("commands.market.list.notice"), false);
        MutableComponent message = Component.empty()
            .append(Translator.tr("commands.market.list.header"));
        String currentMarket = "";
        for (Instrument instrument : Instrument.values()) {
            if (!currentMarket.equals(instrument.market())) {
                currentMarket = instrument.market();
                message.append("\n").append(Translator.tr("commands.market.list.market", marketLabel(currentMarket)));
            }
            Component status = Translator.tr("commands.market.briefing.status.unavailable");
            boolean tradable = false;
            String price = "-";
            String change = "-";
            String changeAmount = "-";
            String movingAverage = "-";
            if (ImyvmFinance.QUOTE_STORE != null) {
                try {
                    Optional<StoredQuote> stored = ImyvmFinance.QUOTE_STORE.findLatest(instrument);
                    if (stored.isPresent()) {
                        StoredQuote value = stored.get();
                        boolean enabled = value.quote().status() == MarketStatus.OPEN;
                        if (enabled && ImyvmFinance.TRADING_STORE != null)
                            enabled = ImyvmFinance.TRADING_STORE.isGlobalTradingEnabled()
                                && ImyvmFinance.TRADING_STORE.isTradingEnabled(instrument);
                        tradable = enabled;
                        status = marketStatus(value, enabled);
                        price = formatPrice(value.quote().priceScaled());
                        change = formatPercent(value.quote().changeBps());
                        changeAmount = formatChangeAmount(value.quote());
                        movingAverage = formatMovingAverage(
                            ImyvmFinance.QUOTE_STORE.findRecentPrices(instrument, 5));
                    }
                } catch (Exception exception) {
                    tradable = false;
                }
            }
            Component item = Translator.tr(
                "commands.market.list.item", instrumentLabel(instrument), price, change, changeAmount, movingAverage).copy();
            if (tradable)
                item = item.copy().withStyle(style -> style
                    .withClickEvent(new ClickEvent.RunCommand(
                        "/imyvm-market estimate " + instrument.commandForm() + " " + ImyvmFinance.TRADING_RULES.minUnits()))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        Translator.tr("commands.market.briefing.buy_hint")))
                    .withUnderlined(true));
            message.append("\n").append(item);
        }

        context.getSource().sendSuccess((Supplier<Component>) () -> message, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int quote(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
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
            context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.label()));
            return 0;
        }

        StoredQuote quote = storedQuote.get();
        boolean tradable = quote.quote().status() == MarketStatus.OPEN;
        if (tradable && ImyvmFinance.TRADING_STORE != null) {
            try {
                tradable = ImyvmFinance.TRADING_STORE.isGlobalTradingEnabled()
                    && ImyvmFinance.TRADING_STORE.isTradingEnabled(instrument);
            } catch (Exception exception) {
                tradable = false;
            }
        }
        Component status = marketStatus(quote, tradable);
        context.getSource().sendSuccess(
            () -> Translator.tr(
                "commands.market.quote.result",
                instrumentLabel(quote.quote().instrument()),
                formatPrice(quote.quote().priceScaled()),
                formatPercent(quote.quote().changeBps()),
                status),
            false);
        return Command.SINGLE_SUCCESS;
    }

    private static Component marketStatus(StoredQuote quote, boolean tradable) {
        if (tradable)
            return Translator.tr("commands.market.briefing.status.open");
        if (quote.quote().status() == MarketStatus.OPEN)
            return Translator.tr("commands.market.briefing.status.paused");
        return Translator.tr("commands.market.briefing.status." + quote.quote().status().name().toLowerCase());
    }

    private static int estimate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
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
            if (!tradingEnabled(context.getSource(), instrument))
                return 0;
            Optional<StoredQuote> storedQuote = ImyvmFinance.QUOTE_STORE.findLatest(instrument);
            if (storedQuote.isEmpty()) {
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.label()));
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
            String command = "/imyvm-market confirm " + createConfirmation(
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
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
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
        if (!requireInitialized(context.getSource()))
            return 0;
        sendPlayerDisclaimer(context.getSource());
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
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", position.instrument().label()));
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
                String command = "/imyvm-market confirm " + createConfirmation(
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
        if (!requireInitialized(context.getSource()))
            return 0;
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
                context.getSource().sendFailure(Translator.tr("commands.market.quote.unavailable", instrument.label()));
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
        return instrument.label();
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

    private static String formatChangeAmount(MarketQuote quote) {
        BigDecimal current = BigDecimal.valueOf(quote.priceScaled(), 4);
        BigDecimal rate = BigDecimal.valueOf(quote.changeBps(), 4);
        if (rate.compareTo(BigDecimal.ONE.negate()) == 0)
            return "-";
        BigDecimal previous = current.divide(BigDecimal.ONE.add(rate), 8, RoundingMode.HALF_UP);
        return current.subtract(previous).setScale(4, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    private static String formatMovingAverage(List<Long> prices) {
        if (prices.size() < 5)
            return "-";
        BigDecimal total = BigDecimal.ZERO;
        for (long price : prices)
            total = total.add(BigDecimal.valueOf(price, 4));
        return total.divide(BigDecimal.valueOf(prices.size()), 4, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }
}
