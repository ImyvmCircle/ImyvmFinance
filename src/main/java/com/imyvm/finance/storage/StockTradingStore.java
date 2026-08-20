package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.trading.StockOrderState;
import com.imyvm.finance.trading.StockTradeState;
import com.imyvm.finance.trading.TradeSide;
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
import java.util.ArrayList;
import java.util.List;
import java.sql.Statement;
import java.util.UUID;

public final class StockTradingStore implements AutoCloseable {
    public record MarketAlert(long id, String alert) {}

    private final Connection connection;

    private StockTradingStore(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS stock_orders (
                    order_id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    transaction_id TEXT NOT NULL UNIQUE,
                    position_id TEXT,
                    symbol TEXT NOT NULL,
                    units INTEGER NOT NULL,
                    amount INTEGER NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            boolean hasPositionId = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(stock_orders)")) {
                while (columns.next()) {
                    if ("position_id".equals(columns.getString("name"))) {
                        hasPositionId = true;
                        break;
                    }
                }
            }
            if (!hasPositionId)
                statement.execute("ALTER TABLE stock_orders ADD COLUMN position_id TEXT");
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
                    closed_at INTEGER,
                    state TEXT NOT NULL
                )
                """);
            ensureColumn(statement, "stock_positions", "closed_at", "closed_at INTEGER");
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
                    realized_profit INTEGER NOT NULL DEFAULT 0,
                    snapshot_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            ensureColumn(statement, "stock_trades", "realized_profit", "realized_profit INTEGER NOT NULL DEFAULT 0");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS leaderboard_daily_notices (
                    notice_date TEXT PRIMARY KEY,
                    announced_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS trading_halts (
                    scope TEXT PRIMARY KEY
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS briefing_opt_outs (
                    player_id TEXT PRIMARY KEY
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS market_alerts (
                    alert_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alert TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS market_alert_receipts (
                    alert_id INTEGER NOT NULL,
                    player_id TEXT NOT NULL,
                    delivered_at INTEGER NOT NULL,
                    PRIMARY KEY (alert_id, player_id),
                    FOREIGN KEY (alert_id) REFERENCES market_alerts(alert_id) ON DELETE CASCADE
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
            statement.execute("""
                CREATE INDEX IF NOT EXISTS market_alerts_created_idx
                ON market_alerts(alert_id)
                """);
        }
    }

    private static void ensureColumn(Statement statement, String table, String name, String definition)
        throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (name.equals(columns.getString("name")))
                    return;
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
    }

    public static StockTradingStore open(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Files.createDirectories(databasePath.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 2000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return new StockTradingStore(connection);
    }

    public synchronized boolean isGlobalTradingEnabled() throws SQLException {
        return !isTradingHalted("GLOBAL");
    }

    public synchronized boolean isTradingEnabled(Instrument instrument) throws SQLException {
        return !isTradingHalted(instrument.symbol()) && isMarketTradingEnabled(instrument.market());
    }

    public synchronized boolean isMarketTradingEnabled(String market) throws SQLException {
        return !isTradingHalted("MARKET:" + market);
    }

    public synchronized void setMarketTradingEnabled(String market, boolean enabled) throws SQLException {
        setTradingEnabled("MARKET:" + market, enabled);
    }

    public synchronized void setGlobalTradingEnabled(boolean enabled) throws SQLException {
        setTradingEnabled("GLOBAL", enabled);
    }

    public synchronized void setTradingEnabled(Instrument instrument, boolean enabled) throws SQLException {
        setTradingEnabled(instrument.symbol(), enabled);
    }

    private boolean isTradingHalted(String scope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM trading_halts WHERE scope = ?")) {
            statement.setString(1, scope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void setTradingEnabled(String scope, boolean enabled) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            enabled ? "DELETE FROM trading_halts WHERE scope = ?" : "INSERT OR IGNORE INTO trading_halts(scope) VALUES (?)")) {
            statement.setString(1, scope);
            statement.executeUpdate();
        }
    }

    public synchronized boolean isBriefingOptedOut(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM briefing_opt_outs WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized void setBriefingOptedOut(UUID playerId, boolean optedOut) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            optedOut
                ? "INSERT OR IGNORE INTO briefing_opt_outs (player_id) VALUES (?)"
                : "DELETE FROM briefing_opt_outs WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    public synchronized java.util.Set<UUID> findBriefingOptOuts() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_id FROM briefing_opt_outs");
             ResultSet result = statement.executeQuery()) {
            java.util.Set<UUID> players = new java.util.HashSet<>();
            while (result.next())
                players.add(UUID.fromString(result.getString(1)));
            return players;
        }
    }

    public synchronized long enqueueMarketAlert(String alert, long createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO market_alerts(alert, created_at) VALUES (?, ?)")) {
            statement.setString(1, alert);
            statement.setLong(2, createdAt);
            statement.executeUpdate();
        }
        long id;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            result.next();
            id = result.getLong(1);
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM market_alerts WHERE alert_id NOT IN "
                + "(SELECT alert_id FROM market_alerts ORDER BY alert_id DESC LIMIT 10)");
        }
        return id;
    }

    public synchronized List<MarketAlert> findUndeliveredMarketAlerts(UUID playerId) throws SQLException {
        List<MarketAlert> alerts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT a.alert_id, a.alert
            FROM market_alerts a
            LEFT JOIN market_alert_receipts r
                ON r.alert_id = a.alert_id AND r.player_id = ?
            WHERE r.alert_id IS NULL
            ORDER BY a.alert_id ASC
            LIMIT 10
            """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    alerts.add(new MarketAlert(result.getLong(1), result.getString(2)));
            }
        }
        return alerts;
    }

    public synchronized void markMarketAlertDelivered(UUID playerId, long alertId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO market_alert_receipts(alert_id, player_id, delivered_at)
            VALUES (?, ?, ?)
            """)) {
            statement.setLong(1, alertId);
            statement.setString(2, playerId.toString());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
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
                     snapshot_id, position_id, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, orderId.toString());
                statement.setString(2, transaction.playerId().toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, estimate.instrument().symbol());
                statement.setLong(5, estimate.units());
                statement.setLong(6, estimate.settlementAmount());
                statement.setString(7, estimate.snapshotId());
                statement.setString(8, positionId.toString());
                statement.setString(9, StockOrderState.PENDING_FINANCE.name());
                statement.setLong(10, createdAtEpochMillis);
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
                   position_value, buy_fee, buy_snapshot_id, bought_at, earliest_sell_at, state
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
                    result.getLong("buy_fee"),
                    result.getString("buy_snapshot_id"),
                    result.getLong("bought_at"),
                    result.getLong("earliest_sell_at"),
                    StockOrderState.valueOf(result.getString("state"))));
            }
        }
    }

    public synchronized long positionCount(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM stock_positions WHERE player_id = ? AND state IN (?, ?, ?)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, StockOrderState.ACTIVE.name());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.PENDING_MANUAL.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public synchronized List<StoredPosition> findPositions(UUID playerId) throws SQLException {
        return findPositions(playerId, 100L, 0L);
    }

    public synchronized List<StoredPosition> findPositions(UUID playerId, long limit, long offset) throws SQLException {
        if (limit < 1 || limit > 100 || offset < 0)
            throw new IllegalArgumentException("position page is invalid");

        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT position_id, player_id, symbol, remaining_units, frozen_units,
                   position_value, buy_fee, buy_snapshot_id, bought_at, earliest_sell_at, state
            FROM stock_positions
            WHERE player_id = ? AND state IN (?, ?, ?)
            ORDER BY bought_at ASC
            LIMIT ? OFFSET ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, StockOrderState.ACTIVE.name());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.PENDING_MANUAL.name());
            statement.setLong(5, limit);
            statement.setLong(6, offset);
            List<StoredPosition> positions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Instrument instrument = Instrument.fromSymbol(result.getString("symbol"));
                    if (instrument == null)
                        throw new SQLException("position contains a non-whitelisted symbol");
                    positions.add(new StoredPosition(
                        UUID.fromString(result.getString("position_id")),
                        UUID.fromString(result.getString("player_id")),
                        instrument,
                        result.getLong("remaining_units"),
                        result.getLong("frozen_units"),
                        result.getLong("position_value"),
                        result.getLong("buy_fee"),
                        result.getString("buy_snapshot_id"),
                        result.getLong("bought_at"),
                        result.getLong("earliest_sell_at"),
                        StockOrderState.valueOf(result.getString("state"))));
                }
            }
            return positions;
        }
    }

    public synchronized long tradeCount(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM stock_trades WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public synchronized List<StoredTrade> findRecentTrades(UUID playerId, int limit) throws SQLException {
        return findRecentTrades(playerId, limit, 0L);
    }

    public synchronized List<StoredTrade> findRecentTrades(UUID playerId, int limit, long offset) throws SQLException {
        if (limit < 1)
            throw new IllegalArgumentException("trade history limit must be positive");
        limit = Math.min(limit, 20);
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT trade_id, order_id, player_id, symbol, side, units,
                   execution_price_scaled, gross_amount, fee_amount,
                   settlement_amount, snapshot_id, state, created_at
            FROM stock_trades
            WHERE player_id = ?
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, limit);
            statement.setLong(3, offset);
            List<StoredTrade> trades = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Instrument instrument = Instrument.fromSymbol(result.getString("symbol"));
                    if (instrument == null)
                        throw new SQLException("trade contains a non-whitelisted symbol");
                    trades.add(new StoredTrade(
                        UUID.fromString(result.getString("trade_id")),
                        UUID.fromString(result.getString("order_id")),
                        UUID.fromString(result.getString("player_id")),
                        instrument,
                        TradeSide.valueOf(result.getString("side")),
                        result.getLong("units"),
                        result.getLong("execution_price_scaled"),
                        result.getLong("gross_amount"),
                        result.getLong("fee_amount"),
                        result.getLong("settlement_amount"),
                        result.getString("snapshot_id"),
                        StockTradeState.valueOf(result.getString("state")),
                        result.getLong("created_at")));
                }
            }
            return trades;
        }
    }

    public synchronized java.util.Optional<StoredOrder> findOrder(UUID transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT order_id, player_id, transaction_id, symbol, units, amount,
                   snapshot_id, position_id, state, created_at
            FROM stock_orders
            WHERE transaction_id = ?
            """)) {
            statement.setString(1, transactionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return java.util.Optional.empty();
                Instrument instrument = Instrument.fromSymbol(result.getString("symbol"));
                if (instrument == null)
                    throw new SQLException("order contains a non-whitelisted symbol");
                return java.util.Optional.of(new StoredOrder(
                    UUID.fromString(result.getString("order_id")),
                    UUID.fromString(result.getString("player_id")),
                    UUID.fromString(result.getString("transaction_id")),
                    result.getString("position_id") == null ? null : UUID.fromString(result.getString("position_id")),
                    instrument,
                    result.getLong("units"),
                    result.getLong("amount"),
                    result.getString("snapshot_id"),
                    StockOrderState.valueOf(result.getString("state")),
                    result.getLong("created_at")));
            }
        }
    }

    public synchronized List<StoredOrder> findPendingManualOrders() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT order_id, player_id, transaction_id, symbol, units, amount,
                   snapshot_id, position_id, state, created_at
            FROM stock_orders
            WHERE state = ?
            ORDER BY created_at ASC
            LIMIT 100
            """)) {
            statement.setString(1, StockOrderState.PENDING_MANUAL.name());
            List<StoredOrder> orders = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Instrument instrument = Instrument.fromSymbol(result.getString("symbol"));
                    if (instrument == null)
                        throw new SQLException("order contains a non-whitelisted symbol");
                    orders.add(new StoredOrder(
                        UUID.fromString(result.getString("order_id")),
                        UUID.fromString(result.getString("player_id")),
                        UUID.fromString(result.getString("transaction_id")),
                        (result.getString("position_id") == null ? null : UUID.fromString(result.getString("position_id"))),
                        instrument,
                        result.getLong("units"),
                        result.getLong("amount"),
                        result.getString("snapshot_id"),
                        StockOrderState.valueOf(result.getString("state")),
                        result.getLong("created_at")));
                }
            }
            return orders;
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
                    (order_id, player_id, transaction_id, symbol, units, amount, snapshot_id, position_id, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, orderId.toString());
                statement.setString(2, transaction.playerId().toString());
                statement.setString(3, transaction.transactionId().toString());
                statement.setString(4, estimate.instrument().symbol());
                statement.setLong(5, estimate.units());
                statement.setLong(6, estimate.settlementAmount());
                statement.setString(7, estimate.snapshotId());
                statement.setString(8, positionId.toString());
                statement.setString(9, StockOrderState.PENDING_FINANCE.name());
                statement.setLong(10, createdAtEpochMillis);
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
            long positionValue = remainingUnits == 0 ? 0 : BigDecimal.valueOf(position.positionValue())
                .multiply(BigDecimal.valueOf(remainingUnits))
                .divide(BigDecimal.valueOf(position.remainingUnits()), 0, RoundingMode.FLOOR).longValueExact();
            long buyFee = remainingUnits == 0 ? 0 : BigDecimal.valueOf(position.buyFee())
                .multiply(BigDecimal.valueOf(remainingUnits))
                .divide(BigDecimal.valueOf(position.remainingUnits()), 0, RoundingMode.FLOOR).longValueExact();
            long soldCost = position.positionValue() - positionValue;
            long soldBuyFee = position.buyFee() - buyFee;
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_positions
                SET remaining_units = ?, frozen_units = ?, position_value = ?, buy_fee = ?, state = ?,
                    closed_at = CASE WHEN ? = 0 THEN ? ELSE closed_at END
                WHERE position_id = ? AND frozen_units >= ?
                """)) {
                statement.setLong(1, remainingUnits);
                statement.setLong(2, frozenUnits);
                statement.setLong(3, positionValue);
                statement.setLong(4, buyFee);
                statement.setString(5, remainingUnits == 0 ? StockOrderState.CLOSED.name() : StockOrderState.ACTIVE.name());
                statement.setLong(6, remainingUnits);
                statement.setLong(7, System.currentTimeMillis());
                statement.setString(8, positionId.toString());
                statement.setLong(9, units);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("stock position changed concurrently");
            }
            updateOrderState(transactionId, StockOrderState.ACTIVE);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE stock_trades SET realized_profit = settlement_amount - ? - ? WHERE transaction_id = ? AND side = ?")) {
                statement.setLong(1, soldCost);
                statement.setLong(2, soldBuyFee);
                statement.setString(3, transactionId.toString());
                statement.setString(4, TradeSide.SELL.name());
                statement.executeUpdate();
            }
            updateTradeState(transactionId, StockTradeState.CONFIRMED);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized void releaseSell(UUID transactionId, UUID positionId, long units) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_positions
                SET frozen_units = frozen_units - ?, state = ?
                WHERE position_id = ? AND state = ?
                  AND frozen_units >= ?
                """)) {
                statement.setLong(1, units);
                statement.setString(2, StockOrderState.ACTIVE.name());
                statement.setString(3, positionId.toString());
                statement.setString(4, StockOrderState.PENDING_MANUAL.name());
                statement.setLong(5, units);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("stock position changed concurrently");
            }
            updateOrderState(transactionId, StockOrderState.CANCELLED);
            updateTradeState(transactionId, StockTradeState.CANCELLED);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized void pruneBefore(long cutoffEpochMillis) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            deleteTradesBefore(cutoffEpochMillis);
            deleteCancelledOrdersBefore(cutoffEpochMillis);
            deleteClosedPositionsBefore(cutoffEpochMillis);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private void deleteTradesBefore(long cutoffEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM stock_trades
            WHERE created_at < ? AND state IN (?, ?)
            """)) {
            statement.setLong(1, cutoffEpochMillis);
            statement.setString(2, StockTradeState.CONFIRMED.name());
            statement.setString(3, StockTradeState.CANCELLED.name());
            statement.executeUpdate();
        }
    }

    private void deleteCancelledOrdersBefore(long cutoffEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM stock_orders
            WHERE created_at < ? AND state = ?
            """)) {
            statement.setLong(1, cutoffEpochMillis);
            statement.setString(2, StockOrderState.CANCELLED.name());
            statement.executeUpdate();
        }
    }

    private void deleteClosedPositionsBefore(long cutoffEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM stock_orders
            WHERE position_id IN (
                SELECT position_id FROM stock_positions
                WHERE state = ? AND closed_at < ?
            )
            """)) {
            statement.setString(1, StockOrderState.CLOSED.name());
            statement.setLong(2, cutoffEpochMillis);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM stock_positions
            WHERE state = ? AND closed_at < ?
            """)) {
            statement.setString(1, StockOrderState.CLOSED.name());
            statement.setLong(2, cutoffEpochMillis);
            statement.executeUpdate();
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

    public synchronized java.util.List<UUID> findPlayerIds() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT player_id FROM stock_positions WHERE state IN (?, ?, ?) UNION SELECT DISTINCT player_id FROM stock_trades WHERE side = ? AND state = ?")) {
            statement.setString(1, StockOrderState.ACTIVE.name());
            statement.setString(2, StockOrderState.PENDING_FINANCE.name());
            statement.setString(3, StockOrderState.PENDING_MANUAL.name());
            statement.setString(4, TradeSide.SELL.name());
            statement.setString(5, StockTradeState.CONFIRMED.name());
            List<UUID> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(UUID.fromString(result.getString(1))); }
            return ids;
        }
    }

    public synchronized long realizedProfit(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(realized_profit), 0) FROM stock_trades WHERE player_id = ? AND side = ? AND state = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, TradeSide.SELL.name());
            statement.setString(3, StockTradeState.CONFIRMED.name());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        }
    }

    public synchronized boolean claimLeaderboardNotice(String date) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO leaderboard_daily_notices(notice_date, announced_at) VALUES (?, ?)")) {
            statement.setString(1, date);
            statement.setLong(2, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
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
              AND state IN (?, ?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, transactionId.toString());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.ACTIVE.name());
            statement.setString(5, StockOrderState.PENDING_MANUAL.name());
            statement.executeUpdate();
        }
    }

    private void updatePositionState(UUID transactionId, StockOrderState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_positions SET state = ?
            WHERE transaction_id = ?
              AND state IN (?, ?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, transactionId.toString());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.ACTIVE.name());
            statement.setString(5, StockOrderState.PENDING_MANUAL.name());
            statement.executeUpdate();
        }
    }

    private void updatePositionStateById(UUID positionId, StockOrderState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_positions SET state = ?
            WHERE position_id = ?
              AND state IN (?, ?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, positionId.toString());
            statement.setString(3, StockOrderState.PENDING_FINANCE.name());
            statement.setString(4, StockOrderState.ACTIVE.name());
            statement.setString(5, StockOrderState.PENDING_MANUAL.name());
            statement.executeUpdate();
        }
    }

    private void updateTradeState(UUID transactionId, StockTradeState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stock_trades SET state = ?
            WHERE transaction_id = ?
              AND state IN (?, ?, ?)
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, transactionId.toString());
            statement.setString(3, StockTradeState.PENDING_FINANCE.name());
            statement.setString(4, StockTradeState.CONFIRMED.name());
            statement.setString(5, StockTradeState.PENDING_MANUAL.name());
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
