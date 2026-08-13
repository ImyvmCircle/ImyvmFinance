package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.transaction.StockOperation;
import com.imyvm.finance.transaction.StockTransaction;
import com.imyvm.finance.transaction.StockTransactionState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

public final class StockTransactionStore implements AutoCloseable {
    private final Connection connection;

    private StockTransactionStore(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS stock_transactions (
                    transaction_id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    reference_id TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    economy_result TEXT,
                    failure_stage TEXT,
                    failure_reason TEXT,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    next_retry_at INTEGER,
                    external_reference TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
            ensureColumn(statement, "failure_stage", "failure_stage TEXT");
            ensureColumn(statement, "failure_reason", "failure_reason TEXT");
            ensureColumn(statement, "retry_count", "retry_count INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "next_retry_at", "next_retry_at INTEGER");
            ensureColumn(statement, "external_reference", "external_reference TEXT");
            statement.execute("""
                CREATE INDEX IF NOT EXISTS stock_transactions_player_state_idx
                ON stock_transactions(player_id, state)
                """);
        }
    }

    public record PendingSettlement(
        StockTransaction transaction,
        String failureStage,
        String failureReason,
        int retryCount,
        Long nextRetryAtEpochMillis,
        String externalReference
    ) {
    }

    private static void ensureColumn(Statement statement, String name, String definition)
        throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(stock_transactions)")) {
            while (columns.next()) {
                if (name.equals(columns.getString("name")))
                    return;
            }
        }
        statement.execute("ALTER TABLE stock_transactions ADD COLUMN " + definition);
    }

    public static StockTransactionStore open(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Files.createDirectories(databasePath.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        connection.setAutoCommit(true);
        return new StockTransactionStore(connection);
    }

    public synchronized void createPrepared(StockTransaction transaction) throws SQLException {
        if (transaction.state() != StockTransactionState.PREPARED)
            throw new IllegalArgumentException("new transaction must be PREPARED");

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO stock_transactions
                (transaction_id, player_id, operation, reference_id, symbol,
                 amount, state, economy_result, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, transaction.transactionId().toString());
            statement.setString(2, transaction.playerId().toString());
            statement.setString(3, transaction.operation().name());
            statement.setString(4, transaction.referenceId());
            statement.setString(5, transaction.instrument().symbol());
            statement.setLong(6, transaction.amount());
            statement.setString(7, transaction.state().name());
            statement.setString(8, transaction.economyResult());
            statement.setLong(9, transaction.createdAtEpochMillis());
            statement.setLong(10, transaction.updatedAtEpochMillis());
            statement.executeUpdate();
        }

        StockTransaction existing = find(transaction.transactionId())
            .orElseThrow(() -> new SQLException("transaction was not persisted"));
        if (!existing.equals(transaction))
            throw new IllegalStateException("transaction id already belongs to another stock transaction");
    }

    public synchronized Optional<StockTransaction> find(UUID transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT transaction_id, player_id, operation, reference_id, symbol,
                   amount, state, economy_result, created_at, updated_at
            FROM stock_transactions
            WHERE transaction_id = ?
            """)) {
            statement.setString(1, transactionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return Optional.empty();
                return Optional.of(read(result));
            }
        }
    }

    public synchronized StockTransaction transition(UUID transactionId,
                                                     StockTransactionState nextState,
                                                     String economyResult,
                                                     long updatedAtEpochMillis) throws SQLException {
        StockTransaction current = find(transactionId)
            .orElseThrow(() -> new IllegalArgumentException("unknown stock transaction"));
        if (current.state() == nextState)
            return current;
        if (!isAllowed(current.state(), nextState))
            throw new IllegalStateException(
                "invalid stock transaction transition: "
                    + current.state() + " -> " + nextState);

        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_transactions
            SET state = ?, economy_result = ?, updated_at = ?
            WHERE transaction_id = ? AND state = ?
            """)) {
            statement.setString(1, nextState.name());
            statement.setString(2, economyResult);
            statement.setLong(3, updatedAtEpochMillis);
            statement.setString(4, transactionId.toString());
            statement.setString(5, current.state().name());
            if (statement.executeUpdate() != 1)
                throw new SQLException("stock transaction changed concurrently");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }

        return find(transactionId).orElseThrow();
    }

    public synchronized java.util.List<StockTransaction> findInterruptedTransactions()
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT transaction_id, player_id, operation, reference_id, symbol, amount, state,
                   economy_result, created_at, updated_at
            FROM stock_transactions
            WHERE state IN (?, ?)
            ORDER BY updated_at ASC
            """)) {
            statement.setString(1, StockTransactionState.PREPARED.name());
            statement.setString(2, StockTransactionState.ECONOMY_CONFIRMED.name());
            java.util.List<StockTransaction> transactions = new java.util.ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    transactions.add(read(result));
            }
            return transactions;
        }
    }

    public synchronized int pendingSettlementCount(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM stock_transactions
            WHERE player_id = ? AND state = ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, StockTransactionState.PENDING_MANUAL.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public synchronized PendingSettlement markPending(
        UUID transactionId,
        String failureStage,
        String failureReason,
        Long nextRetryAtEpochMillis,
        long updatedAtEpochMillis
    ) throws SQLException {
        if (failureStage == null || failureStage.isBlank()
            || failureReason == null || failureReason.isBlank())
            throw new IllegalArgumentException("pending settlement details are required");

        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_transactions
            SET state = ?, economy_result = ?, failure_stage = ?, failure_reason = ?,
                retry_count = retry_count + 1, next_retry_at = ?, updated_at = ?
            WHERE transaction_id = ? AND state IN (?, ?, ?)
            """)) {
            statement.setString(1, StockTransactionState.PENDING_MANUAL.name());
            statement.setString(2, failureStage + ":" + failureReason);
            statement.setString(3, failureStage);
            statement.setString(4, failureReason);
            if (nextRetryAtEpochMillis == null)
                statement.setNull(5, Types.BIGINT);
            else
                statement.setLong(5, nextRetryAtEpochMillis);
            statement.setLong(6, updatedAtEpochMillis);
            statement.setString(7, transactionId.toString());
            statement.setString(8, StockTransactionState.PREPARED.name());
            statement.setString(9, StockTransactionState.ECONOMY_CONFIRMED.name());
            statement.setString(10, StockTransactionState.PENDING_MANUAL.name());
            if (statement.executeUpdate() != 1)
                throw new SQLException("stock transaction cannot be marked pending");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }

        return findPendingSettlement(transactionId)
            .orElseThrow(() -> new SQLException("pending settlement was not persisted"));
    }

    public synchronized Optional<PendingSettlement> findPendingSettlement(UUID transactionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT transaction_id, player_id, operation, reference_id, symbol, amount, state,
                   economy_result, created_at, updated_at, failure_stage, failure_reason,
                   retry_count, next_retry_at, external_reference
            FROM stock_transactions
            WHERE transaction_id = ? AND state = ?
            """)) {
            statement.setString(1, transactionId.toString());
            statement.setString(2, StockTransactionState.PENDING_MANUAL.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return Optional.empty();
                long nextRetryAt = result.getLong("next_retry_at");
                return Optional.of(new PendingSettlement(
                    read(result),
                    result.getString("failure_stage"),
                    result.getString("failure_reason"),
                    result.getInt("retry_count"),
                    result.wasNull() ? null : nextRetryAt,
                    result.getString("external_reference")));
            }
        }
    }

    private static boolean isAllowed(StockTransactionState current,
                                     StockTransactionState next) {
        return switch (current) {
            case PREPARED ->
                next == StockTransactionState.ECONOMY_CONFIRMED
                    || next == StockTransactionState.PENDING_MANUAL
                    || next == StockTransactionState.CANCELLED;
            case ECONOMY_CONFIRMED ->
                next == StockTransactionState.FINANCE_CONFIRMED
                    || next == StockTransactionState.PENDING_MANUAL;
            case FINANCE_CONFIRMED -> false;
            case PENDING_MANUAL ->
                next == StockTransactionState.FINANCE_CONFIRMED
                    || next == StockTransactionState.CANCELLED;
            case CANCELLED -> false;
        };
    }

    private static StockTransaction read(ResultSet result) throws SQLException {
        Instrument instrument = Instrument.fromSymbol(result.getString("symbol"));
        if (instrument == null)
            throw new SQLException("transaction contains a non-whitelisted symbol");

        return new StockTransaction(
            UUID.fromString(result.getString("transaction_id")),
            UUID.fromString(result.getString("player_id")),
            StockOperation.valueOf(result.getString("operation")),
            result.getString("reference_id"),
            instrument,
            result.getLong("amount"),
            StockTransactionState.valueOf(result.getString("state")),
            result.getString("economy_result"),
            result.getLong("created_at"),
            result.getLong("updated_at"));
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
