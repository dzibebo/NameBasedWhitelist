package org.mrbonnieg.NameBasedWhitelist;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public class Events implements Listener {
    private final Main plugin;

    public Events(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * Use PlayerLoginEvent (fires before PlayerJoinEvent) so the kick happens
     * before any join broadcast, and use HIGHEST priority to run after other plugins.
     * containsPlayer() hits the in-memory cache — zero I/O on the main thread.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.getConfig().getBoolean("settings.enable")) return;

        if (!plugin.getStorage().isReady()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "[NameBasedWhitelist] ⚠ Database connection is currently unavailable/not ready!\nCheck server console or config.yml credentials.");
            return;
        }

        String username = event.getPlayer().getName().toLowerCase();
        if (!plugin.getStorage().containsPlayer(username)) {
            String kickMsg = HexColorParser.parseHexColors(
                    plugin.getConfig().getString("messages.kick-message"));
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, kickMsg);
        }
    }
}
