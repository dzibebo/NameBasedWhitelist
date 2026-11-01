package org.mrbonnieg.NameBasedWhitelist;

import com.google.common.collect.Lists;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Commands implements CommandExecutor, TabCompleter {
    private final Main plugin;

    public Commands(Main plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Command execution
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(msg("messages.plugin-usage"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                if (!hasManagePermission(sender)) {
                    sender.sendMessage(msg("messages.no-permissions"));
                } else {
                    plugin.reloadConfig();
                    plugin.reloadStorage();
                    sender.sendMessage(msg("messages.plugin-reload"));
                }
                return true;

            case "add":
                if (!hasModifyPermission(sender)) {
                    sender.sendMessage(msg("messages.no-permissions"));
                    return true;
                }
                if (!checkStorageReady(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("messages.plugin-usage"));
                    return true;
                }
                {
                    String playerName = args[1].toLowerCase(Locale.ROOT);
                    if (plugin.getStorage().containsPlayer(playerName)) {
                        sender.sendMessage(msg("messages.player-already-exists").replace("%player%", args[1]));
                    } else {
                        plugin.getStorage().addPlayer(playerName);
                        sender.sendMessage(msg("messages.player-add").replace("%player%", args[1]));
                    }
                }
                return true;

            case "remove":
                if (!hasModifyPermission(sender)) {
                    sender.sendMessage(msg("messages.no-permissions"));
                    return true;
                }
                if (!checkStorageReady(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("messages.plugin-usage"));
                    return true;
                }
                {
                    String playerName = args[1].toLowerCase(Locale.ROOT);
                    if (!plugin.getStorage().containsPlayer(playerName)) {
                        sender.sendMessage(msg("messages.player-not-found").replace("%player%", args[1]));
                    } else {
                        plugin.getStorage().removePlayer(playerName);
                        sender.sendMessage(msg("messages.player-remove").replace("%player%", args[1]));
                    }
                }
                return true;

            case "enable":
                if (!hasManagePermission(sender)) {
                    sender.sendMessage(msg("messages.no-permissions"));
                } else {
                    plugin.setWhitelistEnabled(true);
                    sender.sendMessage(msg("messages.plugin-enable"));
                }
                return true;

            case "disable":
                if (!hasManagePermission(sender)) {
                    sender.sendMessage(msg("messages.no-permissions"));
                } else {
                    plugin.setWhitelistEnabled(false);
                    sender.sendMessage(msg("messages.plugin-disable"));
                }
                return true;

            default:
                sender.sendMessage(msg("messages.plugin-usage"));
                return true;
        }
    }

    private boolean checkStorageReady(CommandSender sender) {
        if (plugin.getStorage() == null || !plugin.getStorage().isReady()) {
            String dbError = plugin.getConfig().getString("messages.database-error");
            if (dbError == null || dbError.trim().isEmpty()) {
                dbError = plugin.getConfig().getString("messages.database-not-ready");
            }
            if (dbError == null || dbError.trim().isEmpty()) {
                dbError = " &#CDDEDFꜰ&#C7DDD3ᴏ&#C1DDC6x&#BEDDC0ᴡ&#BBDCBAᴀ&#AEDCBDʀ&#A1DCC0ᴅ &#94DCC3▢ &#ffffffбᴀзᴀ дᴀʜʜыx ᴇщё ʜᴇ гᴏᴛᴏʙᴀ! пᴏдᴏждиᴛᴇ или ᴄʙяжиᴛᴇᴄь ᴄ ᴀдᴍиʜиᴄᴛᴘᴀциᴇй.";
            }
            sender.sendMessage(HexColorParser.parseHexColors(dbError));
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartsWith(
                    Lists.newArrayList("add", "remove", "enable", "disable", "reload"),
                    args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("add")) {
                if (plugin.getStorage() == null || !plugin.getStorage().isReady()) {
                    return Lists.newArrayList();
                }
                // Suggest online players who are NOT already whitelisted
                List<String> suggestions = new ArrayList<>();
                for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                    String name = player.getName();
                    if (!plugin.getStorage().containsPlayer(name)) {
                        suggestions.add(name);
                    }
                }
                return filterStartsWith(suggestions, args[1]);
            }

            if (sub.equals("remove")) {
                if (plugin.getStorage() == null || !plugin.getStorage().isReady()) {
                    return Lists.newArrayList();
                }
                // Suggest players from the whitelist cache — fast (no DB query)
                return filterStartsWith(new ArrayList<>(plugin.getStorage().getPlayers()), args[1]);
            }
        }

        return Lists.newArrayList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Parse hex colors from the config string. */
    private String msg(String path) {
        return HexColorParser.parseHexColors(plugin.getConfig().getString(path));
    }

    /** Filter a list to entries that start with the given prefix (case-insensitive). */
    private static List<String> filterStartsWith(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String entry : list) {
            if (entry.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(entry);
        }
        return result;
    }

    private boolean hasManagePermission(CommandSender sender) {
        return sender.hasPermission("namebasedwhitelist.manage")
                || sender.hasPermission("namebasedwhitelist.*");
    }

    private boolean hasModifyPermission(CommandSender sender) {
        return sender.hasPermission("namebasedwhitelist.modifylist")
                || sender.hasPermission("namebasedwhitelist.*");
    }
}
