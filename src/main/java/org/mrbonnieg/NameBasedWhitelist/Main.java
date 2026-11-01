package org.mrbonnieg.NameBasedWhitelist;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

public final class Main extends JavaPlugin {
    private Storage storage;

    public Storage getStorage() { return storage; }

    /**
     * Safely updates the settings.enable setting in config.yml while preserving all comments and spacing.
     */
    public void setWhitelistEnabled(boolean enabled) {
        // Update in-memory configuration
        getConfig().set("settings.enable", enabled);

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        try {
            Path path = configFile.toPath();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            boolean inSettings = false;
            boolean updated = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();

                if (trimmed.startsWith("settings:")) {
                    inSettings = true;
                    continue;
                }

                // If we hit another top-level key, we are no longer inside the settings block
                if (inSettings && !line.startsWith(" ") && !line.startsWith("\t") && !line.isEmpty() && !trimmed.startsWith("#")) {
                    inSettings = false;
                }

                if (inSettings && trimmed.startsWith("enable:")) {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex != -1) {
                        lines.set(i, line.substring(0, colonIndex + 1) + " " + enabled);
                        updated = true;
                        break;
                    }
                }
            }

            if (updated) {
                Files.write(path, lines, StandardCharsets.UTF_8);
            } else {
                saveConfig();
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to update enable state in config.yml while preserving comments", e);
            saveConfig();
        }
    }

    @Override
    public void onLoad() {
        initConfiguration();
        initStorage();
    }

    private void initConfiguration() {
        // If config.yml exists but is empty, delete it to force saveDefaultConfig() to copy the default resource.
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists() && configFile.length() == 0) {
            configFile.delete();
        }
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
    }

    private void initStorage() {
        String storageType = getConfig().getString("settings.storage-type", "yml");
        switch (storageType.toLowerCase()) {
            case "mysql":
                storage = new MySqlStorage(this);
                getLogger().log(Level.INFO, "Storage type: MySQL (HikariCP pool)");
                break;
            default:
                storage = new YamlStorage(this);
                getLogger().log(Level.INFO, "Storage type: YAML");
                break;
        }
    }

    public void reloadStorage() {
        if (storage != null) {
            storage.shutdown();
        }
        initStorage();
        if (isEnabled() && storage != null) {
            storage.startTasks();
        }
    }

    @Override
    public void onEnable() {
        if (storage == null) {
            initConfiguration();
            initStorage();
        }

        if (storage != null) {
            storage.startTasks();
        }

        getServer().getPluginManager().registerEvents(new Events(this), this);
        Commands commands = new Commands(this);
        getServer().getPluginCommand("nbwl").setExecutor(commands);
        getServer().getPluginCommand("nbwl").setTabCompleter(commands);
        getLogger().log(Level.INFO, "NameBasedWhitelist v" + getDescription().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            storage.shutdown();
        }
        getLogger().log(Level.INFO, "NameBasedWhitelist disabled");
    }
}
