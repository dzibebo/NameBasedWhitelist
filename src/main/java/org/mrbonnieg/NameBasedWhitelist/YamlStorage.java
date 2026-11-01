package org.mrbonnieg.NameBasedWhitelist;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML-based storage with an in-memory ConcurrentHashMap cache so that
 * containsPlayer() / getPlayers() never touch the disk at runtime.
 * Disk I/O only happens on addPlayer / removePlayer.
 */
public class YamlStorage implements Storage {
    private final Main plugin;
    private final YamlConfiguration whitelist;
    private final File whitelistFile;

    /** In-memory cache for O(1) lookups — no disk reads on the hot path. */
    private final Set<String> cache = ConcurrentHashMap.newKeySet();

    public YamlStorage(Main plugin) {
        this.plugin = plugin;
        this.whitelistFile = new File(plugin.getDataFolder(), "whitelist.yml");
        if (!whitelistFile.exists()) {
            plugin.saveResource("whitelist.yml", false);
        }
        this.whitelist = YamlConfiguration.loadConfiguration(whitelistFile);

        // Populate cache once on startup
        for (String name : whitelist.getStringList("players")) {
            cache.add(name.toLowerCase());
        }
    }

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
        whitelist.set("players", new ArrayList<>(cache));
        saveWhitelist();
        return true;
    }

    @Override
    public boolean removePlayer(String username) {
        String lower = username.toLowerCase();
        if (!cache.remove(lower)) return false;
        whitelist.set("players", new ArrayList<>(cache));
        saveWhitelist();
        return true;
    }

    @Override
    public void saveWhitelist() {
        try {
            whitelist.save(whitelistFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save whitelist.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean isReady() {
        return true;
    }
}
