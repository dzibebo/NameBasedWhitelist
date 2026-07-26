package org.mrbonnieg.NameBasedWhitelist;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class MySqlStorage implements Storage {
    private final Main plugin;
    private volatile HikariDataSource dataSource;

    /** In-memory cache — primary source of truth for reads (thread-safe). */
    private volatile Set<String> cache = ConcurrentHashMap.newKeySet();

    /** Write-behind queues — flushed to DB every FLUSH_INTERVAL_TICKS ticks. */
    private final Set<String> pendingAdds    = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingRemoves = ConcurrentHashMap.newKeySet();

    private volatile boolean ready = false;
    private BukkitTask flushTask;
    private BukkitTask syncTask;
    private BukkitTask retryTask;

    /** Flush every 2 s (async bursts). */
    private static final long FLUSH_INTERVAL_TICKS = 40L;

    public MySqlStorage(Main plugin) {
        this.plugin = plugin;
        // Initialise pool on an async thread so the main thread is never blocked.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            initPool();
            if (!ready) {
                startRetryTimer();
                return;
            }
            startTimers();
        });
    }

    private synchronized void startTimers() {
        if (flushTask != null) flushTask.cancel();
        if (syncTask != null) syncTask.cancel();
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }

        int syncIntervalMins = plugin.getConfig().getInt("database.sync-interval", 3);
        long syncIntervalTicks = 20L * 60L * syncIntervalMins;

        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushPending,
                FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
        syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::syncCacheFromDb,
                syncIntervalTicks, syncIntervalTicks);
    }

    private synchronized void startRetryTimer() {
        if (retryTask != null) return;
        plugin.getLogger().log(Level.WARNING, "MySQL connection is not ready. Automatic retry scheduled every 30 seconds...");
        retryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (ready) {
                if (retryTask != null) {
                    retryTask.cancel();
                    retryTask = null;
                }
                return;
            }
            plugin.getLogger().log(Level.INFO, "Attempting to reconnect MySQL pool...");
            initPool();
            if (ready) {
                startTimers();
            }
        }, 600L, 600L); // 30 seconds
    }

    // -------------------------------------------------------------------------
    // Pool initialisation
    // -------------------------------------------------------------------------

    private synchronized void initPool() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
            FileConfiguration config = plugin.getConfig();
            String host     = config.getString("database.host",     "localhost:3306");
            String database = config.getString("database.name",     "database");
            String username = config.getString("database.username", "root");
            String password = config.getString("database.password", "");

            int  poolSize          = config.getInt("database.pool-size",          10);
            long connectionTimeout = config.getLong("database.connection-timeout", 30_000);
            long idleTimeout       = config.getLong("database.idle-timeout",       600_000);
            long maxLifetime       = config.getLong("database.max-lifetime",       1_800_000);

            // Base options that are always required for correct behaviour
            String baseOptions = "allowPublicKeyRetrieval=true"
                    + "&rewriteBatchedStatements=true"
                    + "&cachePrepStmts=true"
                    + "&prepStmtCacheSize=25"
                    + "&prepStmtCacheSqlLimit=2048"
                    + "&useServerPrepStmts=true";

            // User-defined options from config (override / extend base options)
            String extraOptions = config.getString("database.connectionOptions", "");
            String queryString  = extraOptions.isEmpty()
                    ? baseOptions
                    : baseOptions + "&" + extraOptions;

            HikariConfig hk = new HikariConfig();
            hk.setJdbcUrl("jdbc:mysql://" + host + "/" + database + "?" + queryString);
            hk.setUsername(username);
            hk.setPassword(password);
            hk.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // Pool sizing — keep it reasonable; 10 connections cover ~2000 concurrent look-ups easily
            hk.setMaximumPoolSize(poolSize);
            hk.setMinimumIdle(Math.max(2, poolSize / 5));
            hk.setConnectionTimeout(connectionTimeout);
            hk.setIdleTimeout(idleTimeout);
            hk.setMaxLifetime(maxLifetime);
            hk.setKeepaliveTime(60_000);
            hk.setPoolName("NBWLPool");

            // Fast validation query
            hk.setConnectionTestQuery("SELECT 1");

            dataSource = new HikariDataSource(hk);

            createTable();
            loadCache();
            ready = true;

            plugin.getLogger().log(Level.INFO,
                    "MySQL pool ready (" + poolSize + " conns). Loaded " + cache.size() + " players.");
        } catch (Exception e) {
            ready = false;
            if (dataSource != null && !dataSource.isClosed()) {
                try { dataSource.close(); } catch (Exception ex) {}
            }
            dataSource = null;
            plugin.getLogger().log(Level.SEVERE, "MySQL pool initialisation failed! Check your database settings in config.yml (host, name, username, password). Error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    private void createTable() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) return;
        try (Connection c = dataSource.getConnection();
             Statement  s = c.createStatement()) {
            // VARCHAR(64) — Support long names/Bedrock Geyser prefix crossplay; ENGINE=InnoDB for transactions
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS whitelist (" +
                    "  username VARCHAR(64) NOT NULL," +
                    "  PRIMARY KEY (username)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;");
        }
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    private synchronized void loadCache() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) return;
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        try (Connection c  = dataSource.getConnection();
             Statement  s  = c.createStatement();
             ResultSet  rs = s.executeQuery("SELECT username FROM whitelist;")) {
            while (rs.next()) fresh.add(rs.getString("username").toLowerCase());
        }
        // Merge pending adds so we don't lose writes that haven't been flushed yet
        fresh.addAll(pendingAdds);
        fresh.removeAll(pendingRemoves);
        
        // Swap reference atomically to prevent temporary empty state reads
        this.cache = fresh;
    }

    private void syncCacheFromDb() {
        if (!isReady()) return;
        try {
            flushPending();  // push writes first
            loadCache();     // then re-read (both are synchronized — sequential, never concurrent)
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Cache sync from DB failed!", e);
        }
    }

    // -------------------------------------------------------------------------
    // Write-behind flush (batched INSERT + DELETE in one transaction per call)
    // -------------------------------------------------------------------------

    private synchronized void flushPending() {
        if (!isReady()) return;
        if (pendingAdds.isEmpty() && pendingRemoves.isEmpty()) return;

        Set<String> toAdd    = new HashSet<>(pendingAdds);
        Set<String> toRemove = new HashSet<>(pendingRemoves);
        pendingAdds.clear();
        pendingRemoves.clear();

        boolean success = false;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!toAdd.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO whitelist (username) VALUES (?)" +
                            " ON DUPLICATE KEY UPDATE username = username;")) {
                        for (String name : toAdd) {
                            ps.setString(1, name);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (!toRemove.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM whitelist WHERE username = ?;")) {
                        for (String name : toRemove) {
                            ps.setString(1, name);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                c.commit();
                success = true;
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Rollback failed", ex);
                }
                plugin.getLogger().log(Level.SEVERE,
                        "MySQL flush failed, will retry in " + (FLUSH_INTERVAL_TICKS / 20) + "s", e);
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ex) {
                    // Ignore, connection closing anyway
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "MySQL connection error during flush!", e);
        }

        if (!success) {
            // Put back so the next flush can retry without data loss
            pendingAdds.addAll(toAdd);
            pendingRemoves.addAll(toRemove);
        }
    }

    // -------------------------------------------------------------------------
    // Storage interface
    // -------------------------------------------------------------------------

    @Override
    public List<String> getPlayers() {
        return new ArrayList<>(cache);
    }

    @Override
    public boolean containsPlayer(String username) {
        return cache.contains(username.toLowerCase());
    }

    @Override
    public boolean addPlayer(String username) {
        String lower = username.toLowerCase();
        if (!cache.add(lower)) return false;
        pendingRemoves.remove(lower);
        pendingAdds.add(lower);
        return true;
    }

    @Override
    public boolean removePlayer(String username) {
        String lower = username.toLowerCase();
        if (!cache.remove(lower)) return false;
        pendingAdds.remove(lower);
        pendingRemoves.add(lower);
        return true;
    }

    @Override
    public void saveWhitelist() {
        if (!isReady()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushPending);
    }

    /** Called by Main#onDisable — flush synchronously before the JVM exits. */
    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
        if (syncTask != null) syncTask.cancel();
        if (retryTask != null) retryTask.cancel();
        if (isReady()) {
            flushPending();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        ready = false;
    }

    @Override
    public boolean isReady() {
        return ready && dataSource != null && !dataSource.isClosed();
    }
}
