package com.imyvm.finance.storage;

import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.market.QuoteSnapshot;
import com.imyvm.finance.quote.SimulationFormula;

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
import java.util.Map;
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
                    market_time INTEGER NOT NULL,
                    node_time INTEGER NOT NULL
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
                    quote_origin TEXT NOT NULL DEFAULT 'REAL',
                    PRIMARY KEY (snapshot_id, symbol),
                    FOREIGN KEY (snapshot_id) REFERENCES market_snapshots(snapshot_id)
                )
                """);
            try { statement.execute("ALTER TABLE market_snapshots ADD COLUMN node_time INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
            statement.execute("UPDATE market_snapshots SET node_time = market_time WHERE node_time = 0");
            try { statement.execute("ALTER TABLE market_quotes ADD COLUMN quote_origin TEXT NOT NULL DEFAULT 'REAL'"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_nodes ADD COLUMN input_source TEXT NOT NULL DEFAULT 'REAL'"); } catch (SQLException ignored) { }
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_functions (
                    function_id TEXT PRIMARY KEY, function_type TEXT NOT NULL, updated_at INTEGER NOT NULL
                )
                """);
            try { statement.execute("ALTER TABLE simulation_functions ADD COLUMN active INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_sessions ADD COLUMN function_formula TEXT NOT NULL DEFAULT 'CLAMP(DRIFT_BPS + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)'"); } catch (SQLException ignored) { }
            statement.execute("INSERT OR IGNORE INTO simulation_functions(function_id, function_type, updated_at) VALUES ('robust_seeded_walk', 'CLAMP(DRIFT_BPS + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)', strftime('%s', 'now'))");
            statement.execute("UPDATE simulation_functions SET function_type = 'CLAMP(DRIFT_BPS + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)' WHERE function_id = 'robust_seeded_walk' AND function_type = 'robust_seeded_walk'");
            statement.execute("UPDATE simulation_functions SET active = 1 WHERE function_id = 'robust_seeded_walk' AND NOT EXISTS (SELECT 1 FROM simulation_functions WHERE active = 1)");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_sessions (
                    session_id INTEGER PRIMARY KEY, market TEXT NOT NULL, started_at INTEGER NOT NULL,
                    ended_at INTEGER, function_id TEXT NOT NULL, function_formula TEXT NOT NULL, seed INTEGER NOT NULL, status TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_nodes (
                    session_id INTEGER NOT NULL, node_time INTEGER NOT NULL, symbol TEXT NOT NULL,
                    previous_price INTEGER NOT NULL, fluctuation_bps INTEGER NOT NULL, new_price INTEGER NOT NULL, input_source TEXT NOT NULL DEFAULT 'REAL',
                    PRIMARY KEY (session_id, node_time, symbol), FOREIGN KEY (session_id) REFERENCES simulation_sessions(session_id)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_node_inputs (
                    session_id INTEGER NOT NULL, node_time INTEGER NOT NULL, symbol TEXT NOT NULL,
                    input_index INTEGER NOT NULL, source TEXT NOT NULL, quote_time INTEGER NOT NULL, price_scaled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, node_time, symbol, input_index), FOREIGN KEY (session_id) REFERENCES simulation_sessions(session_id)
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS market_quotes_symbol_idx
                ON market_quotes(symbol)
                """);
            statement.execute("UPDATE simulation_sessions SET ended_at = COALESCE(ended_at, started_at), status = 'ABORTED' WHERE status = 'ACTIVE'");
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
                    (snapshot_id, source, fetched_at, market_time, node_time)
                VALUES (?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, snapshot.snapshotId());
                statement.setString(2, snapshot.source());
                statement.setLong(3, snapshot.fetchedAtEpochMillis());
                statement.setLong(4, snapshot.marketTimeEpochMillis());
                statement.setLong(5, snapshot.nodeTimeEpochMillis());
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO market_quotes
                    (snapshot_id, symbol, name, price_scaled, change_bps, market_status, quote_origin)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
                for (MarketQuote quote : snapshot.quotes()) {
                    statement.setString(1, snapshot.snapshotId());
                    statement.setString(2, quote.instrument().symbol());
                    statement.setString(3, quote.name());
                    statement.setLong(4, quote.priceScaled());
                    statement.setLong(5, quote.changeBps());
                    statement.setString(6, quote.status().name());
                    statement.setString(7, quote.origin().name());
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
            SELECT s.snapshot_id, s.source, s.fetched_at, s.market_time, s.node_time,
                   q.name, q.price_scaled, q.change_bps, q.market_status, q.quote_origin
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
                    result.getLong("node_time"),
                    new MarketQuote(
                        instrument,
                        result.getString("name"),
                        result.getLong("price_scaled"),
                        result.getLong("change_bps"),
                        MarketStatus.parse(result.getString("market_status")),
                        com.imyvm.finance.market.QuoteOrigin.valueOf(result.getString("quote_origin")))));
            }
        }
    }

    public synchronized Map<String, String> findSimulationFunctions() throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT function_id, function_type FROM simulation_functions ORDER BY function_id")) {
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
        }
        return result;
    }

    public synchronized Optional<SimulationFunctionView> findSimulationFunction(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT function_id, function_type, active, updated_at FROM simulation_functions WHERE function_id = ?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { if (row.next()) return Optional.of(new SimulationFunctionView(row.getString(1), row.getString(2), row.getInt(3) != 0, row.getLong(4))); }
        }
        return Optional.empty();
    }

    public synchronized boolean simulationFunctionExists(String id) throws SQLException {
        return findSimulationFunction(id).isPresent();
    }

    public synchronized Map.Entry<String, String> activeSimulationFunction() throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT function_id, function_type FROM simulation_functions WHERE active = 1 LIMIT 1")) {
            if (rows.next()) return Map.entry(rows.getString(1), rows.getString(2));
        }
        return Map.entry("robust_seeded_walk", SimulationFormula.DEFAULT);
    }

    public synchronized void activateSimulationFunction(String id) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM simulation_functions WHERE function_id = ?")) {
                check.setString(1, id);
                try (ResultSet row = check.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("unknown function"); }
            }
            try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE simulation_functions SET active = 0"); }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE simulation_functions SET active = 1 WHERE function_id = ?")) { statement.setString(1, id); statement.executeUpdate(); }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally { connection.setAutoCommit(oldAutoCommit); }
    }

    public synchronized void upsertSimulationFunction(String id, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO simulation_functions(function_id, function_type, updated_at) VALUES (?, ?, ?) ON CONFLICT(function_id) DO UPDATE SET function_type = excluded.function_type, updated_at = excluded.updated_at")) {
            statement.setString(1, id); statement.setString(2, type); statement.setLong(3, System.currentTimeMillis()); statement.executeUpdate();
        }
    }

    public synchronized void deleteSimulationFunction(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM simulation_functions WHERE function_id = ? AND function_id <> 'robust_seeded_walk' AND active = 0")) { statement.setString(1, id); if (statement.executeUpdate() == 0) throw new IllegalArgumentException("function is missing, default, or active"); }
    }

    public synchronized void beginSimulation(long sessionId, String market, long startedAt, String functionId, String formula, long seed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO simulation_sessions(session_id, market, started_at, function_id, function_formula, seed, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
            statement.setLong(1, sessionId); statement.setString(2, market); statement.setLong(3, startedAt);
            statement.setString(4, functionId); statement.setString(5, formula); statement.setLong(6, seed); statement.executeUpdate();
        }
    }

    public synchronized void recordSimulationNode(long sessionId, long nodeTime, String symbol, String inputSource, long previousPrice, long fluctuationBps, long newPrice) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO simulation_nodes(session_id, node_time, symbol, input_source, previous_price, fluctuation_bps, new_price) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, sessionId); statement.setLong(2, nodeTime); statement.setString(3, symbol); statement.setString(4, inputSource);
            statement.setLong(5, previousPrice); statement.setLong(6, fluctuationBps); statement.setLong(7, newPrice); statement.executeUpdate();
        }
    }

    public synchronized void recordSimulationNodeInput(long sessionId, long nodeTime, String symbol, int inputIndex, String source, long quoteTime, long priceScaled) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO simulation_node_inputs(session_id, node_time, symbol, input_index, source, quote_time, price_scaled) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, sessionId); statement.setLong(2, nodeTime); statement.setString(3, symbol); statement.setInt(4, inputIndex);
            statement.setString(5, source); statement.setLong(6, quoteTime); statement.setLong(7, priceScaled); statement.executeUpdate();
        }
    }

    public synchronized void finishSimulation(long sessionId, long endedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE simulation_sessions SET ended_at = ?, status = 'RECOVERED' WHERE session_id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, endedAt); statement.setLong(2, sessionId); statement.executeUpdate();
        }
    }

    public synchronized long simulationNodeCount(long sessionId, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM simulation_nodes WHERE session_id = ? AND symbol = ?")) {
            statement.setLong(1, sessionId); statement.setString(2, symbol);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    public synchronized long simulationNodeCount(long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM simulation_nodes WHERE session_id = ?")) {
            statement.setLong(1, sessionId); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    public synchronized Map.Entry<String, String> simulationFunctionForSession(long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT function_id, function_formula FROM simulation_sessions WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet row = statement.executeQuery()) { if (row.next()) return Map.entry(row.getString(1), row.getString(2)); }
        }
        return Map.entry("robust_seeded_walk", SimulationFormula.DEFAULT);
    }

    public synchronized Optional<SimulationSessionView> findSimulationSession(long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, market, started_at, ended_at, function_id, function_formula, seed, status FROM simulation_sessions WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return Optional.of(new SimulationSessionView(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getObject(4) == null ? null : rows.getLong(4), rows.getString(5), rows.getString(6), rows.getLong(7), rows.getString(8)));
            }
        }
        return Optional.empty();
    }

    public synchronized long simulationSessionCount() throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM simulation_sessions")) { row.next(); return row.getLong(1); }
    }

    public synchronized long simulationNodeTotal(long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM simulation_nodes WHERE session_id = ?")) { statement.setLong(1, sessionId); try (ResultSet row = statement.executeQuery()) { row.next(); return row.getLong(1); } }
    }

    public synchronized List<SimulationSessionView> findSimulationSessions(int limit, int offset) throws SQLException {
        List<SimulationSessionView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, market, started_at, ended_at, function_id, function_formula, seed, status FROM simulation_sessions ORDER BY started_at DESC LIMIT ? OFFSET ?")) {
            statement.setInt(1, limit); statement.setInt(2, offset);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(new SimulationSessionView(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getObject(4) == null ? null : rows.getLong(4), rows.getString(5), rows.getString(6), rows.getLong(7), rows.getString(8))); }
        }
        return result;
    }

    public synchronized List<SimulationNodeView> findSimulationNodes(long sessionId, int limit, int offset) throws SQLException {
        List<SimulationNodeView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, node_time, symbol, input_source, previous_price, fluctuation_bps, new_price FROM simulation_nodes WHERE session_id = ? ORDER BY node_time DESC, symbol LIMIT ? OFFSET ?")) {
            statement.setLong(1, sessionId); statement.setInt(2, limit); statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(new SimulationNodeView(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getLong(6), rows.getLong(7))); }
        }
        return result;
    }

    public synchronized long quoteHistoryCount(Instrument instrument) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM market_quotes WHERE symbol = ?")) {
            statement.setString(1, instrument.symbol());
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    public synchronized List<StoredQuote> findQuoteHistory(Instrument instrument, int limit, int offset) throws SQLException {
        if (limit <= 0 || offset < 0) return List.of();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT s.snapshot_id, s.source, s.fetched_at, s.market_time, s.node_time,
                   q.name, q.price_scaled, q.change_bps, q.market_status, q.quote_origin
            FROM market_quotes q JOIN market_snapshots s ON s.snapshot_id = q.snapshot_id
            WHERE q.symbol = ? ORDER BY s.node_time DESC, s.fetched_at DESC LIMIT ? OFFSET ?
            """)) {
            statement.setString(1, instrument.symbol());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            List<StoredQuote> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new StoredQuote(rows.getString("snapshot_id"), rows.getString("source"),
                    rows.getLong("fetched_at"), rows.getLong("market_time"), rows.getLong("node_time"),
                    new MarketQuote(instrument, rows.getString("name"), rows.getLong("price_scaled"),
                        rows.getLong("change_bps"), MarketStatus.parse(rows.getString("market_status")),
                        com.imyvm.finance.market.QuoteOrigin.valueOf(rows.getString("quote_origin")))));
            }
            return result;
        }
    }

    public synchronized List<StoredQuote> findRecentRealQuotes(Instrument instrument, int limit) throws SQLException {
        if (limit <= 0) return List.of();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT s.snapshot_id, s.source, s.fetched_at, s.market_time, s.node_time,
                   q.name, q.price_scaled, q.change_bps, q.market_status, q.quote_origin
            FROM market_quotes q JOIN market_snapshots s ON s.snapshot_id = q.snapshot_id
            WHERE q.symbol = ? AND q.quote_origin = 'REAL'
            ORDER BY s.node_time DESC LIMIT ?
            """)) {
            statement.setString(1, instrument.symbol());
            statement.setInt(2, limit);
            List<StoredQuote> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new StoredQuote(rows.getString("snapshot_id"), rows.getString("source"),
                    rows.getLong("fetched_at"), rows.getLong("market_time"), rows.getLong("node_time"),
                    new MarketQuote(instrument, rows.getString("name"), rows.getLong("price_scaled"),
                        rows.getLong("change_bps"), MarketStatus.parse(rows.getString("market_status")),
                        com.imyvm.finance.market.QuoteOrigin.REAL)));
            }
            java.util.Collections.reverse(result);
            return result;
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
                   q.name, q.price_scaled, q.change_bps, q.market_status, q.quote_origin
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
                    result.getLong("node_time"),
                    new MarketQuote(instrument, result.getString("name"), result.getLong("price_scaled"),
                        result.getLong("change_bps"), MarketStatus.parse(result.getString("market_status")),
                        com.imyvm.finance.market.QuoteOrigin.valueOf(result.getString("quote_origin")))));
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
