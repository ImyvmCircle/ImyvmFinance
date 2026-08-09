package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.trading.StockOrderState;
import com.imyvm.finance.trading.StockTradeState;
import com.imyvm.finance.trading.TradeEstimate;
import com.imyvm.finance.transaction.StockTransaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class StockTradingStore implements AutoCloseable {
    private final Connection connection;

    private StockTradingStore(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS stock_orders (
                    order_id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    transaction_id TEXT NOT NULL UNIQUE,
                    symbol TEXT NOT NULL,
                    units INTEGER NOT NULL,
                    amount INTEGER NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS stock_positions (
                    position_id TEXT PRIMARY KEY,
                    order_id TEXT NOT NULL UNIQUE,
                    transaction_id TEXT NOT NULL UNIQUE,
                    player_id TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    remaining_units INTEGER NOT NULL,
                    frozen_units INTEGER NOT NULL,
                    position_value INTEGER NOT NULL,
                    buy_snapshot_id TEXT NOT NULL,
                    buy_price_scaled INTEGER NOT NULL,
                    buy_fee INTEGER NOT NULL,
                    bought_at INTEGER NOT NULL,
                    earliest_sell_at INTEGER NOT NULL,
                    state TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS stock_trades (
                    trade_id TEXT PRIMARY KEY,
                    order_id TEXT NOT NULL,
                    transaction_id TEXT NOT NULL UNIQUE,
                    player_id TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    side TEXT NOT NULL,
                    units INTEGER NOT NULL,
                    execution_price_scaled INTEGER NOT NULL,
                    gross_amount INTEGER NOT NULL,
                    fee_amount INTEGER NOT NULL,
                    settlement_amount INTEGER NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS stock_orders_player_state_idx
                ON stock_orders(player_id, state)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS stock_positions_player_state_idx
                ON stock_positions(player_id, state)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS stock_trades_player_created_idx
                ON stock_trades(player_id, created_at)
                """);
        }
    }

    public static StockTradingStore open(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Files.createDirectories(databasePath.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        connection.setAutoCommit(true);
        return new StockTradingStore(connection);
    }

    public synchronized void createPendingBuy(UUID orderId,
                                               UUID positionId,
                                               UUID tradeId,
                                               StockTransaction transaction,
                                               TradeEstimate estimate,
                                               long createdAtEpochMillis,
                                               long earliestSellAtEpochMillis)
        throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO stock_orders
                    (order_id, player_id, transaction_id, symbol, units, amount,
                     snapshot_id, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, orderId.toString());
                statement.setString(2, transaction.playerId().toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, estimate.instrument().symbol());
                statement.setLong(5, estimate.units());
                statement.setLong(6, estimate.settlementAmount());
                statement.setString(7, estimate.snapshotId());
                statement.setString(8, StockOrderState.PENDING_FINANCE.name());
                statement.setLong(9, createdAtEpochMillis);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO stock_positions
                    (position_id, order_id, transaction_id, player_id, symbol,
                     remaining_units, frozen_units, position_value, buy_snapshot_id,
                     buy_price_scaled, buy_fee, bought_at, earliest_sell_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, positionId.toString());
                statement.setString(2, orderId.toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, transaction.playerId().toString());
                statement.setString(5, estimate.instrument().symbol());
                statement.setLong(6, estimate.units());
                statement.setLong(7, 0L);
                statement.setLong(8, estimate.grossAmount());
                statement.setString(9, estimate.snapshotId());
                statement.setLong(10, estimate.executionPriceScaled());
                statement.setLong(11, estimate.feeAmount());
                statement.setLong(12, createdAtEpochMillis);
                statement.setLong(13, earliestSellAtEpochMillis);
                statement.setString(14, StockOrderState.PENDING_FINANCE.name());
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO stock_trades
                    (trade_id, order_id, transaction_id, player_id, symbol, side,
                     units, execution_price_scaled, gross_amount, fee_amount,
                     settlement_amount, snapshot_id, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, tradeId.toString());
                statement.setString(2, orderId.toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, transaction.playerId().toString());
                statement.setString(5, estimate.instrument().symbol());
                statement.setString(6, estimate.side().name());
                statement.setLong(7, estimate.units());
                statement.setLong(8, estimate.executionPriceScaled());
                statement.setLong(9, estimate.grossAmount());
                statement.setLong(10, estimate.feeAmount());
                statement.setLong(11, estimate.settlementAmount());
                statement.setString(12, estimate.snapshotId());
                statement.setString(13, StockTradeState.PENDING_FINANCE.name());
                statement.setLong(14, createdAtEpochMillis);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized void activateBuy(UUID transactionId) throws SQLException {
        updateState(transactionId, StockOrderState.ACTIVE, StockTradeState.CONFIRMED);
    }

    public synchronized void cancel(UUID transactionId) throws SQLException {
        updateState(transactionId, StockOrderState.CANCELLED, StockTradeState.CANCELLED);
    }

    public synchronized void markPendingManual(UUID transactionId) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            updateOrderState(transactionId, StockOrderState.PENDING_MANUAL);
            updatePositionState(transactionId, StockOrderState.PENDING_MANUAL);
            updateTradeState(transactionId, StockTradeState.PENDING_MANUAL);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized void markSellPendingManual(UUID transactionId, UUID positionId) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            updateOrderState(transactionId, StockOrderState.PENDING_MANUAL);
            updatePositionStateById(positionId, StockOrderState.PENDING_MANUAL);
            updateTradeState(transactionId, StockTradeState.PENDING_MANUAL);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized java.util.Optional<StoredPosition> findPosition(UUID positionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT position_id, player_id, symbol, remaining_units, frozen_units,
                   position_value, buy_snapshot_id, bought_at, earliest_sell_at, state
            FROM stock_positions
            WHERE position_id = ?
            """)) {
            statement.setString(1, positionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return java.util.Optional.empty();
                Instrument instrument = Instrument.fromSymbol(result.getString("symbol"));
                if (instrument == null)
                    throw new SQLException("position contains a non-whitelisted symbol");
                return java.util.Optional.of(new StoredPosition(
                    UUID.fromString(result.getString("position_id")),
                    UUID.fromString(result.getString("player_id")),
                    instrument,
                    result.getLong("remaining_units"),
                    result.getLong("frozen_units"),
                    result.getLong("position_value"),
                    result.getString("buy_snapshot_id"),
                    result.getLong("bought_at"),
                    result.getLong("earliest_sell_at"),
                    StockOrderState.valueOf(result.getString("state"))));
            }
        }
    }

    public synchronized void createPendingSell(UUID orderId, UUID tradeId, UUID positionId,
                                                StockTransaction transaction, TradeEstimate estimate,
                                                long createdAtEpochMillis) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            StoredPosition position = findPosition(positionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown stock position"));
            if (position.instrument() != estimate.instrument()
                || position.state() != StockOrderState.ACTIVE
                || position.remainingUnits() - position.frozenUnits() < estimate.units())
                throw new IllegalArgumentException("stock position units are unavailable");

            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_positions SET frozen_units = frozen_units + ?
                WHERE position_id = ? AND state = ?
                  AND remaining_units - frozen_units >= ?
                """)) {
                statement.setLong(1, estimate.units());
                statement.setString(2, positionId.toString());
                statement.setString(3, StockOrderState.ACTIVE.name());
                statement.setLong(4, estimate.units());
                if (statement.executeUpdate() != 1)
                    throw new SQLException("stock position changed concurrently");
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stock_orders
                    (order_id, player_id, transaction_id, symbol, units, amount, snapshot_id, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, orderId.toString());
                statement.setString(2, transaction.playerId().toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, estimate.instrument().symbol());
                statement.setLong(5, estimate.units());
                statement.setLong(6, estimate.settlementAmount());
                statement.setString(7, estimate.snapshotId());
                statement.setString(8, StockOrderState.PENDING_FINANCE.name());
                statement.setLong(9, createdAtEpochMillis);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stock_trades
                    (trade_id, order_id, transaction_id, player_id, symbol, side, units,
                     execution_price_scaled, gross_amount, fee_amount, settlement_amount,
                     snapshot_id, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, tradeId.toString());
                statement.setString(2, orderId.toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, transaction.playerId().toString());
                statement.setString(5, estimate.instrument().symbol());
                statement.setString(6, estimate.side().name());
                statement.setLong(7, estimate.units());
                statement.setLong(8, estimate.executionPriceScaled());
                statement.setLong(9, estimate.grossAmount());
                statement.setLong(10, estimate.feeAmount());
                statement.setLong(11, estimate.settlementAmount());
                statement.setString(12, estimate.snapshotId());
                statement.setString(13, StockTradeState.PENDING_FINANCE.name());
                statement.setLong(14, createdAtEpochMillis);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized void activateSell(UUID transactionId, UUID positionId, long units) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            StoredPosition position = findPosition(positionId)
                .orElseThrow(() -> new SQLException("unknown stock position"));
            if (position.frozenUnits() < units)
                throw new SQLException("stock position is not frozen for this sale");
            long remainingUnits = position.remainingUnits() - units;
            long frozenUnits = position.frozenUnits() - units;
            long positionValue = remainingUnits == 0
                ? 0
                : BigDecimal.valueOf(position.positionValue())
                    .multiply(BigDecimal.valueOf(remainingUnits))
                    .divide(BigDecimal.valueOf(position.remainingUnits()), 0, RoundingMode.FLOOR)
                    .longValueExact();
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_positions
                SET remaining_units = ?, frozen_units = ?, position_value = ?, state = ?
                WHERE position_id = ? AND frozen_units >= ?
                """)) {
                statement.setLong(1, remainingUnits);
                statement.setLong(2, frozenUnits);
                statement.setLong(3, positionValue);
                statement.setString(4, remainingUnits == 0 ? StockOrderState.CLOSED.name() : StockOrderState.ACTIVE.name());
                statement.setString(5, positionId.toString());
                statement.setLong(6, units);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("stock position changed concurrently");
            }
            updateOrderState(transactionId, StockOrderState.ACTIVE);
            updateTradeState(transactionId, StockTradeState.CONFIRMED);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized long dailySellAmount(UUID playerId, long startEpochMillis, long endEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COALESCE(SUM(settlement_amount), 0)
            FROM stock_trades
            WHERE player_id = ? AND side = 'SELL'
              AND created_at >= ? AND created_at < ?
              AND state <> ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, startEpochMillis);
            statement.setLong(3, endEpochMillis);
            statement.setString(4, StockTradeState.CANCELLED.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public synchronized long dailyBuyAmount(UUID playerId,
                                             long startEpochMillis,
                                             long endEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COALESCE(SUM(settlement_amount), 0)
            FROM stock_trades
            WHERE player_id = ? AND side = 'BUY'
              AND created_at >= ? AND created_at < ?
              AND state <> ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, startEpochMillis);
            statement.setLong(3, endEpochMillis);
            statement.setString(4, StockTradeState.CANCELLED.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public synchronized long positionValue(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COALESCE(SUM(position_value), 0)
            FROM stock_positions
            WHERE player_id = ? AND state IN (?, ?, ?)
              AND remaining_units > frozen_units
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, StockOrderState.ACTIVE.name());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.PENDING_MANUAL.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private void updateState(UUID transactionId,
                             StockOrderState orderState,
                             StockTradeState tradeState) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            updateOrderState(transactionId, orderState);
            updatePositionState(transactionId, orderState);
            updateTradeState(transactionId, tradeState);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private void updateOrderState(UUID transactionId, StockOrderState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_orders SET state = ?
            WHERE transaction_id = ?
              AND state IN (?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, transactionId.toString());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.ACTIVE.name());
            statement.executeUpdate();
        }
    }

    private void updatePositionState(UUID transactionId, StockOrderState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_positions SET state = ?
            WHERE transaction_id = ?
              AND state IN (?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, transactionId.toString());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.ACTIVE.name());
            statement.executeUpdate();
        }
    }

    private void updatePositionStateById(UUID positionId, StockOrderState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_positions SET state = ?
            WHERE position_id = ?
              AND state IN (?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, positionId.toString());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.ACTIVE.name());
            statement.executeUpdate();
        }
    }

    private void updateTradeState(UUID transactionId, StockTradeState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_trades SET state = ?
            WHERE transaction_id = ?
              AND state IN (?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, transactionId.toString());
            statement.setString(3, StockTradeState.PENDING_FINANCE.name());
            statement.setString(4, StockTradeState.CONFIRMED.name());
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
