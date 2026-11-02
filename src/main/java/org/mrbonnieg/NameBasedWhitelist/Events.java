package org.mrbonnieg.NameBasedWhitelist;

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

        if (plugin.getStorage() == null || !plugin.getStorage().isReady()) {
            String dbNotReady = plugin.getConfig().getString("messages.database-not-ready");
            if (dbNotReady == null || dbNotReady.trim().isEmpty()) {
                dbNotReady = plugin.getConfig().getString("messages.database-error");
            }
            if (dbNotReady == null || dbNotReady.trim().isEmpty()) {
                dbNotReady = "&cDatabase is not ready yet!\n&7Please wait a moment or contact administration.";
            }
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, HexColorParser.parseHexColors(dbNotReady));
            return;
        }

        String username = event.getPlayer().getName().toLowerCase();
        if (!plugin.getStorage().containsPlayer(username)) {
            String kickMsg = plugin.getConfig().getString("messages.kick-message");
            if (kickMsg == null || kickMsg.trim().isEmpty()) {
                kickMsg = "&cYou are not whitelisted on this server!";
            }
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, HexColorParser.parseHexColors(kickMsg));
        }
    }
}
