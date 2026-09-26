package fr.noltox.hcplugins.advancementsbuttonredirect;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAdvancementTab;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class AdvancementClickListener implements PacketListener, Listener {

    private static final String MENU_REDIRECT_ID = "heavencube:menu_redirect";

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final PlayerManager playerManager;
    private final ConfiguredCommandExecutor commandExecutor;
    private final ConcurrentHashMap<UUID, User> pendingCommands = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    AdvancementClickListener(
            JavaPlugin plugin,
            PlayerManager playerManager,
            ConfiguredCommandExecutor commandExecutor
    ) {
        this.plugin = plugin;
        this.scheduler = plugin.getServer().getScheduler();
        this.playerManager = playerManager;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!active || event.getPacketType() != PacketType.Play.Client.ADVANCEMENT_TAB) {
            return;
        }

        WrapperPlayClientAdvancementTab packet = new WrapperPlayClientAdvancementTab(event);
        if (packet.getAction() != WrapperPlayClientAdvancementTab.Action.OPENED_TAB
                || packet.getTabId().filter(MENU_REDIRECT_ID::equals).isEmpty()) {
            return;
        }

        event.setCancelled(true);
        User user = event.getUser();

        try {
            user.sendPacketSilently(new WrapperPlayServerCloseWindow());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING, exception,
                    () -> "Impossible de fermer immédiatement l'écran des progrès pour " + user.getUUID() + '.');
        }

        queueCommand(user);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingCommands.remove(event.getPlayer().getUniqueId());
    }

    void shutdown() {
        active = false;
        pendingCommands.clear();
    }

    private void queueCommand(User user) {
        UUID playerId = user.getUUID();
        if (playerId == null || !active || !commandExecutor.isConfigured()
                || !reserveCommand(playerId, user)) {
            return;
        }

        if (!active) {
            pendingCommands.remove(playerId, user);
            return;
        }

        try {
            scheduler.runTask(
                    plugin,
                    () -> runPendingCommand(playerId, user)
            );
        } catch (IllegalPluginAccessException exception) {
            pendingCommands.remove(playerId, user);
            if (active) {
                plugin.getLogger().log(Level.WARNING, "Impossible de planifier la commande configurée.", exception);
            }
        }
    }

    private boolean reserveCommand(UUID playerId, User user) {
        while (active) {
            User pendingUser = pendingCommands.putIfAbsent(playerId, user);
            if (pendingUser == null) {
                return true;
            }
            if (pendingUser == user) {
                return false;
            }
            if (pendingCommands.replace(playerId, pendingUser, user)) {
                return true;
            }
        }
        return false;
    }

    private void runPendingCommand(UUID playerId, User expectedUser) {
        try {
            if (!active || !plugin.isEnabled() || pendingCommands.get(playerId) != expectedUser) {
                return;
            }

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || playerManager.getUser(player) != expectedUser) {
                return;
            }

            commandExecutor.run(player);
        } finally {
            pendingCommands.remove(playerId, expectedUser);
        }
    }
}
