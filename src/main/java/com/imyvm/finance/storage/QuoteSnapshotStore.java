package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.market.QuoteSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class QuoteSnapshotStore implements AutoCloseable {
    private final Connection connection;

    private QuoteSnapshotStore(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 2000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS market_snapshots (
                    snapshot_id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    fetched_at INTEGER NOT NULL,
                    market_time INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS market_quotes (
                    snapshot_id TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    name TEXT NOT NULL,
                    price_scaled INTEGER NOT NULL,
                    change_bps INTEGER NOT NULL,
                    market_status TEXT NOT NULL,
                    PRIMARY KEY (snapshot_id, symbol),
                    FOREIGN KEY (snapshot_id) REFERENCES market_snapshots(snapshot_id)
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS market_quotes_symbol_idx
                ON market_quotes(symbol)
                """);
        }
    }

    public static QuoteSnapshotStore open(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Files.createDirectories(databasePath.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        connection.setAutoCommit(true);
        return new QuoteSnapshotStore(connection);
    }

    public synchronized void save(QuoteSnapshot snapshot) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO market_snapshots
                    (snapshot_id, source, fetched_at, market_time)
                VALUES (?, ?, ?, ?)
                """)) {
                statement.setString(1, snapshot.snapshotId());
                statement.setString(2, snapshot.source());
                statement.setLong(3, snapshot.fetchedAtEpochMillis());
                statement.setLong(4, snapshot.marketTimeEpochMillis());
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO market_quotes
                    (snapshot_id, symbol, name, price_scaled, change_bps, market_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
                for (MarketQuote quote : snapshot.quotes()) {
                    statement.setString(1, snapshot.snapshotId());
                    statement.setString(2, quote.instrument().symbol());
                    statement.setString(3, quote.name());
                    statement.setLong(4, quote.priceScaled());
                    statement.setLong(5, quote.changeBps());
                    statement.setString(6, quote.status().name());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public synchronized Optional<StoredQuote> findLatest(Instrument instrument) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT s.snapshot_id, s.source, s.fetched_at, s.market_time,
                   q.name, q.price_scaled, q.change_bps, q.market_status
            FROM market_quotes q
            JOIN market_snapshots s ON s.snapshot_id = q.snapshot_id
            WHERE q.symbol = ?
            ORDER BY s.fetched_at DESC
            LIMIT 1
            """)) {
            statement.setString(1, instrument.symbol());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return Optional.empty();

                return Optional.of(new StoredQuote(
                    result.getString("snapshot_id"),
                    result.getString("source"),
                    result.getLong("fetched_at"),
                    result.getLong("market_time"),
                    new MarketQuote(
                        instrument,
                        result.getString("name"),
                        result.getLong("price_scaled"),
                        result.getLong("change_bps"),
                        MarketStatus.parse(result.getString("market_status")))));
            }
        }
    }

    public synchronized List<Long> findRecentPrices(Instrument instrument, int limit) throws SQLException {
        if (limit <= 0)
            return List.of();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT q.price_scaled
            FROM market_quotes q
            JOIN market_snapshots s ON s.snapshot_id = q.snapshot_id
            WHERE q.symbol = ?
            ORDER BY s.fetched_at DESC
            LIMIT ?
            """)) {
            statement.setString(1, instrument.symbol());
            statement.setInt(2, limit);
            List<Long> prices = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    prices.add(result.getLong("price_scaled"));
            }
            return prices;
        }
    }

    public synchronized Optional<StoredQuote> find(Instrument instrument, String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT s.snapshot_id, s.source, s.fetched_at, s.market_time,
                   q.name, q.price_scaled, q.change_bps, q.market_status
            FROM market_quotes q
            JOIN market_snapshots s ON s.snapshot_id = q.snapshot_id
            WHERE q.symbol = ? AND s.snapshot_id = ?
            """)) {
            statement.setString(1, instrument.symbol());
            statement.setString(2, snapshotId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return Optional.empty();
                return Optional.of(new StoredQuote(
                    result.getString("snapshot_id"), result.getString("source"),
                    result.getLong("fetched_at"), result.getLong("market_time"),
                    new MarketQuote(instrument, result.getString("name"), result.getLong("price_scaled"),
                        result.getLong("change_bps"), MarketStatus.parse(result.getString("market_status")))));
            }
        }
    }

    public synchronized void pruneBefore(long cutoffEpochMillis) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement quotes = connection.prepareStatement("""
                DELETE FROM market_quotes
                WHERE snapshot_id IN (
                    SELECT snapshot_id FROM market_snapshots
                    WHERE fetched_at < ? AND snapshot_id NOT IN (
                        SELECT buy_snapshot_id FROM stock_positions WHERE state <> ?
                        UNION
                        SELECT snapshot_id FROM stock_orders WHERE state IN (?, ?)
                    )
                )
                """)) {
                quotes.setLong(1, cutoffEpochMillis);
                quotes.setString(2, com.imyvm.finance.trading.StockOrderState.CLOSED.name());
                quotes.setString(3, com.imyvm.finance.trading.StockOrderState.PENDING_FINANCE.name());
                quotes.setString(4, com.imyvm.finance.trading.StockOrderState.PENDING_MANUAL.name());
                quotes.executeUpdate();
            }
            try (PreparedStatement snapshots = connection.prepareStatement("""
                DELETE FROM market_snapshots
                WHERE fetched_at < ? AND snapshot_id NOT IN (
                    SELECT buy_snapshot_id FROM stock_positions WHERE state <> ?
                    UNION
                    SELECT snapshot_id FROM stock_orders WHERE state IN (?, ?)
                )
                """)) {
                snapshots.setLong(1, cutoffEpochMillis);
                snapshots.setString(2, com.imyvm.finance.trading.StockOrderState.CLOSED.name());
                snapshots.setString(3, com.imyvm.finance.trading.StockOrderState.PENDING_FINANCE.name());
                snapshots.setString(4, com.imyvm.finance.trading.StockOrderState.PENDING_MANUAL.name());
                snapshots.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
