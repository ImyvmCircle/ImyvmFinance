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
import java.util.UUID;

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
            statement.execute("CREATE TABLE IF NOT EXISTS simulation_layer_functions (layer TEXT NOT NULL CHECK (layer IN ('LONG', 'MEDIUM', 'SHORT')), function_id TEXT NOT NULL, formula TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (layer, function_id))");
            statement.execute("INSERT OR IGNORE INTO simulation_layer_functions(layer, function_id, formula, active) VALUES ('LONG', 'trend', 'TREND_BPS', 1)");
            statement.execute("INSERT OR IGNORE INTO simulation_layer_functions(layer, function_id, formula, active) VALUES ('MEDIUM', 'neutral', '0', 1)");
            statement.execute("INSERT OR IGNORE INTO simulation_layer_functions(layer, function_id, formula, active) VALUES ('SHORT', 'noise', 'VOLATILITY_BPS * RANDOM', 1)");
            statement.execute("CREATE TABLE IF NOT EXISTS simulation_session_layers (session_id INTEGER NOT NULL, layer TEXT NOT NULL, function_id TEXT NOT NULL, formula TEXT NOT NULL, PRIMARY KEY (session_id, layer))");
            statement.execute("CREATE TABLE IF NOT EXISTS simulation_node_layers (session_id INTEGER NOT NULL, node_time INTEGER NOT NULL, symbol TEXT NOT NULL, layer TEXT NOT NULL, parameters TEXT NOT NULL, result_bps REAL NOT NULL, PRIMARY KEY (session_id, node_time, symbol, layer))");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_sessions (
                    session_id INTEGER PRIMARY KEY, session_uuid TEXT NOT NULL UNIQUE, market TEXT NOT NULL, started_at INTEGER NOT NULL,
                    ended_at INTEGER, function_id TEXT NOT NULL, function_formula TEXT NOT NULL, seed INTEGER NOT NULL,
                    interval_millis INTEGER NOT NULL DEFAULT 180000, interval_tolerance_millis INTEGER NOT NULL DEFAULT 45000,
                    factor INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL
                )
                """);
            try { statement.execute("ALTER TABLE simulation_sessions ADD COLUMN function_formula TEXT NOT NULL DEFAULT 'CLAMP(TREND_BPS + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)'"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_sessions ADD COLUMN session_uuid TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_sessions ADD COLUMN interval_millis INTEGER NOT NULL DEFAULT 180000"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_sessions ADD COLUMN interval_tolerance_millis INTEGER NOT NULL DEFAULT 45000"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_sessions ADD COLUMN factor INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
            try (ResultSet rows = statement.executeQuery("SELECT session_id FROM simulation_sessions WHERE session_uuid = '' OR session_uuid IS NULL")) {
                List<Long> missing = new ArrayList<>();
                while (rows.next()) missing.add(rows.getLong(1));
                try (PreparedStatement update = connection.prepareStatement("UPDATE simulation_sessions SET session_uuid = ? WHERE session_id = ?")) {
                    for (long id : missing) { update.setString(1, "legacy-" + id); update.setLong(2, id); update.addBatch(); }
                    update.executeBatch();
                }
            }
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_nodes (
                    session_id INTEGER NOT NULL, node_time INTEGER NOT NULL, symbol TEXT NOT NULL,
                    previous_price INTEGER NOT NULL, fluctuation_bps INTEGER NOT NULL, new_price INTEGER NOT NULL,
                    log_return REAL NOT NULL DEFAULT 0, factor INTEGER NOT NULL DEFAULT 0, input_source TEXT NOT NULL DEFAULT 'REAL',
                    PRIMARY KEY (session_id, node_time, symbol), FOREIGN KEY (session_id) REFERENCES simulation_sessions(session_id)
                )
                """);
            try { statement.execute("ALTER TABLE simulation_nodes ADD COLUMN input_source TEXT NOT NULL DEFAULT 'REAL'"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_nodes ADD COLUMN log_return REAL NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE simulation_nodes ADD COLUMN factor INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_node_inputs (
                    session_id INTEGER NOT NULL, node_time INTEGER NOT NULL, symbol TEXT NOT NULL,
                    input_index INTEGER NOT NULL, source TEXT NOT NULL, quote_time INTEGER NOT NULL, price_scaled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, node_time, symbol, input_index), FOREIGN KEY (session_id) REFERENCES simulation_sessions(session_id)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_controls (
                    symbol TEXT PRIMARY KEY, factor INTEGER NOT NULL CHECK (factor BETWEEN 0 AND 5), updated_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_session_factors (
                    session_id INTEGER NOT NULL, symbol TEXT NOT NULL, factor INTEGER NOT NULL CHECK (factor BETWEEN 0 AND 5),
                    PRIMARY KEY (session_id, symbol), FOREIGN KEY (session_id) REFERENCES simulation_sessions(session_id)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS simulation_states (
                    session_id INTEGER NOT NULL, symbol TEXT NOT NULL, trend_state REAL NOT NULL, iteration INTEGER NOT NULL,
                    PRIMARY KEY (session_id, symbol), FOREIGN KEY (session_id) REFERENCES simulation_sessions(session_id)
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

    public synchronized Map<String, String> simulationLayerFunctions(String layer) throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT function_id, formula FROM simulation_layer_functions WHERE layer = ? ORDER BY function_id")) { statement.setString(1, layer); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.put(rows.getString(1), rows.getString(2)); } }
        return result;
    }

    public synchronized void activateSimulationLayer(String layer, String id) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM simulation_layer_functions WHERE layer = ? AND function_id = ?")) { check.setString(1, layer); check.setString(2, id); try (ResultSet rows = check.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("unknown layer function"); } }
        try (PreparedStatement clear = connection.prepareStatement("UPDATE simulation_layer_functions SET active = 0 WHERE layer = ?"); PreparedStatement activate = connection.prepareStatement("UPDATE simulation_layer_functions SET active = 1 WHERE layer = ? AND function_id = ?")) { clear.setString(1, layer); clear.executeUpdate(); activate.setString(1, layer); activate.setString(2, id); activate.executeUpdate(); }
    }

    public synchronized Map<String, String> activeSimulationLayerFormulas() throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT layer, formula FROM simulation_layer_functions WHERE active = 1 ORDER BY layer")) {
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
        }
        if (result.size() != 3) throw new IllegalStateException("simulation layers are incomplete");
        return result;
    }

    public synchronized Map<String, String> simulationLayers(long sessionId) throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT layer, formula FROM simulation_session_layers WHERE session_id = ? ORDER BY layer")) { statement.setLong(1, sessionId); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.put(rows.getString(1), rows.getString(2)); } }
        return result;
    }

    public synchronized Map<String, String> simulationSessionLayerFunctionIds(long sessionId) throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT layer, function_id FROM simulation_session_layers WHERE session_id = ? ORDER BY layer")) { statement.setLong(1, sessionId); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.put(rows.getString(1), rows.getString(2)); } }
        return result;
    }

    public synchronized Map<String, Integer> simulationSessionFactors(long sessionId) throws SQLException {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT symbol, factor FROM simulation_session_factors WHERE session_id = ? ORDER BY symbol")) {
            statement.setLong(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.put(rows.getString(1), rows.getInt(2)); }
        }
        return result;
    }

    public synchronized void freezeSimulationLayers(long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO simulation_session_layers(session_id, layer, function_id, formula) SELECT ?, layer, function_id, formula FROM simulation_layer_functions WHERE active = 1")) { statement.setLong(1, sessionId); statement.executeUpdate(); }
    }

    public synchronized void recordSimulationNodeLayer(long sessionId, long nodeTime, String symbol, String layer, String parameters, double resultBps) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO simulation_node_layers(session_id, node_time, symbol, layer, parameters, result_bps) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, sessionId); statement.setLong(2, nodeTime); statement.setString(3, symbol); statement.setString(4, layer); statement.setString(5, parameters); statement.setDouble(6, resultBps); statement.executeUpdate();
        }
    }

    public synchronized List<String> simulationNodeLayers(long sessionId, String symbol, long nodeTime) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT layer, parameters, result_bps FROM simulation_node_layers WHERE session_id = ? AND symbol = ? AND node_time = ? ORDER BY layer")) { statement.setLong(1, sessionId); statement.setString(2, symbol); statement.setLong(3, nodeTime); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getDouble(3)); } }
        return result;
    }

    public synchronized void beginSimulation(long sessionId, String market, long startedAt, String functionId, String formula, long seed,
                                              long intervalMillis, long intervalToleranceMillis) throws SQLException {
        beginSimulation(sessionId, market, startedAt, functionId, formula, seed, intervalMillis, intervalToleranceMillis, 0);
    }

    public synchronized void beginSimulation(long sessionId, String market, long startedAt, String functionId, String formula, long seed,
                                              long intervalMillis, long intervalToleranceMillis, int factor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO simulation_sessions(session_id, session_uuid, market, started_at, function_id, function_formula, seed, interval_millis, interval_tolerance_millis, factor, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
            statement.setLong(1, sessionId); statement.setString(2, UUID.randomUUID().toString()); statement.setString(3, market); statement.setLong(4, startedAt);
            statement.setString(5, functionId); statement.setString(6, formula); statement.setLong(7, seed);
            statement.setLong(8, intervalMillis); statement.setLong(9, intervalToleranceMillis); statement.setInt(10, factor); statement.executeUpdate();
        }
    }

    public synchronized void recordSimulationNode(long sessionId, long nodeTime, String symbol, String inputSource, long previousPrice, long fluctuationBps, long newPrice) throws SQLException {
        recordSimulationNode(sessionId, nodeTime, symbol, inputSource, previousPrice, fluctuationBps, newPrice, 0);
    }

    public synchronized void recordSimulationNode(long sessionId, long nodeTime, String symbol, String inputSource, long previousPrice, long fluctuationBps, long newPrice, int factor) throws SQLException {
        double logReturn = previousPrice > 0 && newPrice > 0 ? Math.log((double) newPrice / previousPrice) : 0.0;
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO simulation_nodes(session_id, node_time, symbol, input_source, previous_price, fluctuation_bps, new_price, log_return, factor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, sessionId); statement.setLong(2, nodeTime); statement.setString(3, symbol); statement.setString(4, inputSource);
            statement.setLong(5, previousPrice); statement.setLong(6, fluctuationBps); statement.setLong(7, newPrice); statement.setDouble(8, logReturn); statement.setInt(9, factor); statement.executeUpdate();
        }
    }

    public synchronized void recordSimulationNodeInput(long sessionId, long nodeTime, String symbol, int inputIndex, String source, long quoteTime, long priceScaled) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO simulation_node_inputs(session_id, node_time, symbol, input_index, source, quote_time, price_scaled) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, sessionId); statement.setLong(2, nodeTime); statement.setString(3, symbol); statement.setInt(4, inputIndex);
            statement.setString(5, source); statement.setLong(6, quoteTime); statement.setLong(7, priceScaled); statement.executeUpdate();
        }
    }

    public synchronized int simulationFactor(String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT factor FROM simulation_controls WHERE symbol = ?")) {
            statement.setString(1, symbol);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }

    public synchronized void setSimulationFactor(String symbol, int factor) throws SQLException {
        if (factor < 0 || factor > 5) throw new IllegalArgumentException("factor must be between 0 and 5");
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO simulation_controls(symbol, factor, updated_at) VALUES (?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET factor = excluded.factor, updated_at = excluded.updated_at")) {
            statement.setString(1, symbol); statement.setInt(2, factor); statement.setLong(3, System.currentTimeMillis()); statement.executeUpdate();
        }
    }

    public synchronized Map<String, Integer> findSimulationFactors() throws SQLException {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT symbol, factor FROM simulation_controls ORDER BY symbol")) {
            while (rows.next()) result.put(rows.getString(1), rows.getInt(2));
        }
        return result;
    }

    public synchronized int simulationFactorForSession(long sessionId, String symbol, int configuredFactor) throws SQLException {
        if (configuredFactor < 0 || configuredFactor > 5) throw new IllegalArgumentException("factor must be between 0 and 5");
        try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO simulation_session_factors(session_id, symbol, factor) VALUES (?, ?, ?)")) {
            insert.setLong(1, sessionId); insert.setString(2, symbol); insert.setInt(3, configuredFactor); insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT factor FROM simulation_session_factors WHERE session_id = ? AND symbol = ?")) {
            select.setLong(1, sessionId); select.setString(2, symbol);
            try (ResultSet row = select.executeQuery()) { if (row.next()) return row.getInt(1); }
        }
        return configuredFactor;
    }

    public synchronized Optional<SimulationStateView> findSimulationState(long sessionId, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, symbol, trend_state, iteration FROM simulation_states WHERE session_id = ? AND symbol = ?")) {
            statement.setLong(1, sessionId); statement.setString(2, symbol);
            try (ResultSet row = statement.executeQuery()) { if (row.next()) return Optional.of(new SimulationStateView(row.getLong(1), row.getString(2), row.getDouble(3), row.getInt(4))); }
        }
        return Optional.empty();
    }

    public synchronized void saveSimulationState(long sessionId, String symbol, double trendState, int iteration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO simulation_states(session_id, symbol, trend_state, iteration) VALUES (?, ?, ?, ?) ON CONFLICT(session_id, symbol) DO UPDATE SET trend_state = excluded.trend_state, iteration = excluded.iteration")) {
            statement.setLong(1, sessionId); statement.setString(2, symbol); statement.setDouble(3, trendState); statement.setInt(4, iteration); statement.executeUpdate();
        }
    }

    public synchronized void finishSimulation(long sessionId, long endedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE simulation_sessions SET ended_at = ?, status = 'RECOVERED' WHERE session_id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, endedAt); statement.setLong(2, sessionId); statement.executeUpdate();
        }
    }

    public synchronized void abortSimulation(long sessionId, long endedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE simulation_sessions SET ended_at = ?, status = 'ABORTED' WHERE session_id = ? AND status = 'ACTIVE'")) {
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

    public synchronized Optional<SimulationSessionView> findSimulationSession(long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, session_uuid, market, started_at, ended_at, function_id, function_formula, seed, interval_millis, interval_tolerance_millis, factor, status FROM simulation_sessions WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return Optional.of(new SimulationSessionView(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getLong(4), rows.getObject(5) == null ? null : rows.getLong(5), rows.getString(6), rows.getString(7), rows.getLong(8), rows.getLong(9), rows.getLong(10), rows.getInt(11), rows.getString(12)));
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, session_uuid, market, started_at, ended_at, function_id, function_formula, seed, interval_millis, interval_tolerance_millis, factor, status FROM simulation_sessions ORDER BY started_at DESC LIMIT ? OFFSET ?")) {
            statement.setInt(1, limit); statement.setInt(2, offset);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(new SimulationSessionView(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getLong(4), rows.getObject(5) == null ? null : rows.getLong(5), rows.getString(6), rows.getString(7), rows.getLong(8), rows.getLong(9), rows.getLong(10), rows.getInt(11), rows.getString(12))); }
        }
        return result;
    }

    public synchronized List<SimulationNodeView> findSimulationNodes(long sessionId, int limit, int offset) throws SQLException {
        List<SimulationNodeView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, node_time, symbol, input_source, previous_price, fluctuation_bps, new_price, log_return, factor FROM simulation_nodes WHERE session_id = ? ORDER BY node_time DESC, symbol LIMIT ? OFFSET ?")) {
            statement.setLong(1, sessionId); statement.setInt(2, limit); statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(new SimulationNodeView(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getLong(6), rows.getLong(7), rows.getDouble(8), rows.getInt(9))); }
        }
        return result;
    }

    public synchronized List<SimulationNodeInputView> findSimulationNodeInputs(long sessionId, String symbol, long nodeTime) throws SQLException {
        List<SimulationNodeInputView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT session_id, node_time, symbol, input_index, source, quote_time, price_scaled FROM simulation_node_inputs WHERE session_id = ? AND symbol = ? AND node_time = ? ORDER BY input_index")) {
            statement.setLong(1, sessionId); statement.setString(2, symbol); statement.setLong(3, nodeTime);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SimulationNodeInputView(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getInt(4), rows.getString(5), rows.getLong(6), rows.getLong(7)));
            }
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
            SELECT s.snapshot_id, s.source, s.fetched_at, s.market_time, s.node_time,
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
