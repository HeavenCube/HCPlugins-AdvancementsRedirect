package fr.noltox.hcplugins.advancementsbuttonredirect;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import fr.noltox.hcplugins.core.api.config.BukkitYaml;
import fr.noltox.hcplugins.advancementsbuttonredirect.command.AdvancementsCommand;
import fr.noltox.hcplugins.core.api.HCPluginsCore;
import fr.noltox.hcplugins.core.api.command.CoreCommandRegistration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Redirects the client-only advancement tab to a configurable console command.
 */
public final class HCAdvancementsRedirect extends JavaPlugin {

    private static final String CONSOLE_COMMAND_KEY = "console-command";

    private final List<PacketListenerCommon> registeredPacketListeners = new ArrayList<>();
    private EventManager packetEventManager;
    private AdvancementInjector advancementInjector;
    private AdvancementClickListener advancementClickListener;
    private ConfiguredCommandExecutor commandExecutor;
    private CoreCommandRegistration commandRegistration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String configuredConsoleCommand;
        try {
            configuredConsoleCommand = loadConfiguredCommand();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Impossible de charger config.yml.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        commandExecutor = new ConfiguredCommandExecutor(this, configuredConsoleCommand);
        if (!commandExecutor.isConfigured()) {
            getLogger().severe(
                    "La configuration \"console-command\" est erronée. Vérifiez le fichier de configuration."
            );
        }

        PacketEventsAPI<?> packetEventsApi = PacketEvents.getAPI();
        if (!getServer().getPluginManager().isPluginEnabled("packetevents") || packetEventsApi == null) {
            getLogger().severe("PacketEvents doit être installé et activé pour utiliser ce plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PlayerManager playerManager = packetEventsApi.getPlayerManager();
        advancementInjector = new AdvancementInjector(this, playerManager);
        advancementClickListener = new AdvancementClickListener(
                this,
                playerManager,
                commandExecutor
        );
        packetEventManager = packetEventsApi.getEventManager();

        try {
            registeredPacketListeners.add(packetEventManager
                    .registerListener(advancementInjector, PacketListenerPriority.NORMAL));
            registeredPacketListeners.add(packetEventManager
                    .registerListener(advancementClickListener, PacketListenerPriority.NORMAL));

            getServer().getPluginManager().registerEvents(advancementInjector, this);
            getServer().getPluginManager().registerEvents(advancementClickListener, this);
            for (Player player : Bukkit.getOnlinePlayers()) {
                advancementInjector.scheduleFallback(player.getUniqueId());
            }

            AdvancementsCommand commands = new AdvancementsCommand(
                    this::reloadConfiguredCommand,
                    commandExecutor::isConfigured
            );
            commandRegistration = HCPluginsCore.require(this).register(
                    this,
                    "advancementsredirect",
                    "Redirection du menu Progrès",
                    List.of(),
                    commands
            );
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Impossible d'initialiser le plugin.", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private boolean reloadConfiguredCommand() {
        String configuredConsoleCommand;
        try {
            configuredConsoleCommand = loadConfiguredCommand();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Impossible de recharger config.yml.", exception);
            return false;
        }

        commandExecutor.setConfiguredCommand(configuredConsoleCommand);
        if (!commandExecutor.isConfigured()) {
            getLogger().warning("La configuration \"console-command\" est vide ou absente après le reload.");
        }
        return true;
    }

    private String loadConfiguredCommand() {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        FileConfiguration candidate = BukkitYaml.load(configPath);
        String command = candidate.getString(CONSOLE_COMMAND_KEY);
        return command == null ? "" : command.strip();
    }

    @Override
    public void onDisable() {
        if (commandRegistration != null) {
            try {
                commandRegistration.close();
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Impossible de désenregistrer les commandes.", exception);
            }
        }
        if (advancementClickListener != null) {
            advancementClickListener.shutdown();
        }
        if (advancementInjector != null) {
            advancementInjector.shutdown();
        }

        EventManager eventManager = packetEventManager;
        if (eventManager != null && !registeredPacketListeners.isEmpty()) {
            try {
                eventManager.unregisterListeners(registeredPacketListeners.toArray(PacketListenerCommon[]::new));
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Impossible de désenregistrer les listeners PacketEvents.", exception);
            }
        }
        registeredPacketListeners.clear();
        getServer().getScheduler().cancelTasks(this);

    }
}
