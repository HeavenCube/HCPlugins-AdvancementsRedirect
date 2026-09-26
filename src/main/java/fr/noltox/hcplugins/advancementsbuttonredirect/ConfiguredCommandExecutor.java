package fr.noltox.hcplugins.advancementsbuttonredirect;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.logging.Level;

final class ConfiguredCommandExecutor {

    private final JavaPlugin plugin;
    private volatile String configuredCommand;

    ConfiguredCommandExecutor(JavaPlugin plugin, @Nullable String configuredCommand) {
        this.plugin = plugin;
        setConfiguredCommand(configuredCommand);
    }

    boolean isConfigured() {
        return !configuredCommand.isBlank();
    }

    void setConfiguredCommand(@Nullable String configuredCommand) {
        this.configuredCommand = configuredCommand == null ? "" : configuredCommand;
    }

    void run(Player player) {
        String commandTemplate = configuredCommand;
        if (commandTemplate.isBlank()) {
            return;
        }

        String command = commandTemplate.replace("{player}", player.getName());
        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                plugin.getLogger().warning(() -> "La commande configurée n'a pas fonctionné correctement : " + command);
            }
        } catch (CommandException exception) {
            plugin.getLogger().log(
                    Level.SEVERE, exception,
                    () -> "Échec lors de l'exécution de la commande configurée : " + command);
        }
    }
}
