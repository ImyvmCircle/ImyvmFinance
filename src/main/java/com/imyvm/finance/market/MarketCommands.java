package com.imyvm.finance.market;

import com.imyvm.finance.Translator;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.Supplier;

public final class MarketCommands {
    private MarketCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("market")
            .then(Commands.literal("list")
                .executes(MarketCommands::list)));
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
}
