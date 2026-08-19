package com.imyvm.finance.economy;

import com.imyvm.economy.api.DatabaseApi;
import com.imyvm.economy.api.PlayerWallet;
import com.imyvm.finance.storage.StockTransactionStore;
import com.imyvm.finance.transaction.StockOperation;
import com.imyvm.finance.transaction.StockTransaction;
import com.imyvm.finance.transaction.StockTransactionState;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class StockEconomySettlement {
    private static final Logger LOGGER = LoggerFactory.getLogger("imyvm_finance/economy");

    private final StockTransactionStore transactionStore;

    public StockEconomySettlement(StockTransactionStore transactionStore) {
        this.transactionStore = transactionStore;
    }

    public EconomySettlementResult settle(Player player,
                                          StockTransaction transaction) {
        requirePlayer(transaction, player);
        if (transaction.state() != StockTransactionState.PREPARED)
            throw new IllegalStateException("stock transaction is not PREPARED");

        return switch (transaction.operation()) {
            case BUY -> debit(player, transaction);
            case SELL, REFUND -> credit(player, transaction);
        };
    }

    private EconomySettlementResult debit(Player player,
                                          StockTransaction transaction) {
        try {
            PlayerWallet wallet = DatabaseApi.getInstance().getPlayer(player);
            if (!wallet.takeMoney(transaction.amount())) {
                StockTransactionState state = transition(
                    transaction,
                    StockTransactionState.CANCELLED,
                    "insufficient_balance");
                return new EconomySettlementResult(state, transaction.amount());
            }

            StockTransactionState state = transition(
                transaction,
                StockTransactionState.ECONOMY_CONFIRMED,
                "debit_accepted");
            return new EconomySettlementResult(state, transaction.amount());
        } catch (Exception exception) {
            return markPending(transaction, "debit_uncertain", exception);
        }
    }

    private EconomySettlementResult credit(Player player,
                                           StockTransaction transaction) {
        try {
            DatabaseApi.getInstance().getPlayer(player).addMoney(transaction.amount());
            StockTransactionState state = transition(
                transaction,
                StockTransactionState.ECONOMY_CONFIRMED,
                "credit_accepted");
            return new EconomySettlementResult(state, transaction.amount());
        } catch (Exception exception) {
            return markPending(transaction, "credit_uncertain", exception);
        }
    }

    private StockTransactionState transition(StockTransaction transaction,
                                             StockTransactionState state,
                                             String result) {
        try {
            return transactionStore.transition(
                transaction.transactionId(),
                state,
                result,
                System.currentTimeMillis()).state();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to persist stock settlement state", exception);
        }
    }

    private EconomySettlementResult markPending(StockTransaction transaction,
                                                 String result,
                                                 Exception cause) {
        try {
            StockTransactionState state = transactionStore.markPending(
                transaction.transactionId(),
                result,
                cause.getClass().getSimpleName(),
                null,
                System.currentTimeMillis()).transaction().state();
            return new EconomySettlementResult(state, transaction.amount());
        } catch (Exception persistenceFailure) {
            LOGGER.error(
                "Stock transaction {} is uncertain and could not be marked PENDING_MANUAL",
                transaction.transactionId(),
                persistenceFailure);
            return new EconomySettlementResult(
                StockTransactionState.PENDING_MANUAL,
                transaction.amount());
        }
    }

    public static Player offlinePlayer(ServerLevel level, GameProfile profile) {
        return new Player(level, profile) {

            public GameType gameMode() {
                return GameType.SURVIVAL;
            }
        };
    }

    private static void requirePlayer(StockTransaction transaction,
                                      Player player) {
        if (player == null)
            throw new IllegalArgumentException("stock settlement requires an online player");
        UUID playerId = player.getUUID();
        if (!playerId.equals(transaction.playerId()))
            throw new IllegalArgumentException("stock transaction player mismatch");
    }
}
