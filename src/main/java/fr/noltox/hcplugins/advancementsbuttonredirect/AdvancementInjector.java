package fr.noltox.hcplugins.advancementsbuttonredirect;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.advancements.Advancement;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementDisplay;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementHolder;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipDisplay;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAdvancements;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * PacketEvents 2.13.0 exposes mixed nullness metadata on its fluent item and
 * advancement APIs. Eclipse therefore reports unchecked null conversions for
 * constants and values which PacketEvents guarantees to be present.
 */
@SuppressWarnings("null")
final class AdvancementInjector implements PacketListener, Listener {

    private static final ResourceLocation MENU_REDIRECT_ID = new ResourceLocation("heavencube", "menu_redirect");
    private static final ResourceLocation STONE_BACKGROUND = new ResourceLocation("minecraft", "gui/advancements/backgrounds/stone");
    private static final ResourceLocation EMPTY_ITEM_MODEL = new ResourceLocation("nexo", "vide");
    private static final long FALLBACK_DELAY_TICKS = 5L;

    private final JavaPlugin plugin;
    private final PlayerManager playerManager;
    private final Set<UUID> injectedPlayers = ConcurrentHashMap.newKeySet();
    // Bukkit lifecycle callbacks and fallback tasks access this map only from the server thread.
    private final Map<UUID, BukkitTask> pendingFallbacks = new HashMap<>();
    private volatile boolean active = true;

    AdvancementInjector(JavaPlugin plugin, PlayerManager playerManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
    }

    private static boolean containsMenuRedirect(List<AdvancementHolder> advancements) {
        return advancements.stream()
                .anyMatch(advancement -> MENU_REDIRECT_ID.equals(advancement.getIdentifier()));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!active) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        injectedPlayers.remove(playerId);
        scheduleFallback(playerId);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        injectedPlayers.remove(playerId);
        cancelFallback(playerId);
    }

    void scheduleFallback(UUID playerId) {
        if (!active) {
            return;
        }

        cancelFallback(playerId);
        BukkitTask fallback = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    pendingFallbacks.remove(playerId);
                    sendFallback(playerId);
                },
                FALLBACK_DELAY_TICKS
        );
        pendingFallbacks.put(playerId, fallback);
    }

    void shutdown() {
        active = false;
        pendingFallbacks.values().forEach(BukkitTask::cancel);
        pendingFallbacks.clear();
        injectedPlayers.clear();
    }

    private void cancelFallback(UUID playerId) {
        BukkitTask fallback = pendingFallbacks.remove(playerId);
        if (fallback != null) {
            fallback.cancel();
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!active || event.getPacketType() != PacketType.Play.Server.UPDATE_ADVANCEMENTS) {
            return;
        }

        WrapperPlayServerUpdateAdvancements packet = new WrapperPlayServerUpdateAdvancements(event);
        if (!packet.isReset()) {
            return;
        }

        List<AdvancementHolder> addedAdvancements = packet.getAddedAdvancements();
        if (!containsMenuRedirect(addedAdvancements)) {
            List<AdvancementHolder> augmentedAdvancements = new ArrayList<>(addedAdvancements.size() + 1);
            augmentedAdvancements.add(createMenuRedirectAdvancement(event.getUser()));
            augmentedAdvancements.addAll(addedAdvancements);
            packet.setAddedAdvancements(augmentedAdvancements);
            event.markForReEncode(true);
        }

        UUID playerId = event.getUser().getUUID();
        if (playerId != null) {
            // A later PacketEvents listener can still cancel this packet at a higher priority.
            event.getTasksAfterSend().add(() -> recordSuccessfulInjection(event, playerId));
        }
    }

    private void recordSuccessfulInjection(PacketSendEvent event, UUID playerId) {
        if (!active || event.isCancelled()) {
            return;
        }
        injectedPlayers.add(playerId);
        if (!active) {
            injectedPlayers.remove(playerId);
        }
    }

    private void sendFallback(UUID playerId) {
        if (!active || !plugin.isEnabled() || injectedPlayers.contains(playerId)) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        User user = playerManager.getUser(player);
        if (user == null) {
            plugin.getLogger().warning(() -> "PacketEvents n'a pas détecté " + player.getName()
                    + ". Impossible d'injecter le progrès de redirection.");
            return;
        }

        WrapperPlayServerUpdateAdvancements packet = new WrapperPlayServerUpdateAdvancements(
                false,
                List.of(createMenuRedirectAdvancement(user)),
                Set.of(),
                Map.of(),
                false
        );
        user.sendPacketSilently(packet);
        if (active && plugin.isEnabled() && player.isOnline() && playerManager.getUser(player) == user) {
            injectedPlayers.add(playerId);
        }
    }

    private AdvancementHolder createMenuRedirectAdvancement(User user) {
        AdvancementDisplay display = new AdvancementDisplay(
                Component.text("Menu"),
                Component.text("Ouvrir le menu"),
                createMenuIcon(user),
                AdvancementType.TASK,
                STONE_BACKGROUND,
                false,
                false,
                0.0F,
                0.0F
        );
        Advancement advancement = new Advancement(null, display, List.of(List.of("trigger")), false);
        return new AdvancementHolder(MENU_REDIRECT_ID, advancement);
    }

    private ItemStack createMenuIcon(User user) {
        NBTCompound publicBukkitValues = new NBTCompound();
        publicBukkitValues.setTag("nexo:id", new NBTString("vide"));

        NBTCompound customData = new NBTCompound();
        customData.setTag("PublicBukkitValues", publicBukkitValues);

        return ItemStack.builder()
                .user(user)
                .type(ItemTypes.PAPER)
                .amount(1)
                .component(ComponentTypes.ITEM_NAME, Component.text("vide"))
                .component(ComponentTypes.ITEM_MODEL, new ItemModel(EMPTY_ITEM_MODEL))
                .component(ComponentTypes.CUSTOM_DATA, customData)
                .component(ComponentTypes.TOOLTIP_DISPLAY, new ItemTooltipDisplay(true, Set.of()))
                .build();
    }
}
