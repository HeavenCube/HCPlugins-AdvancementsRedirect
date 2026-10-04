package fr.noltox.hcplugins.advancementsbuttonredirect;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAdvancements;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AdvancementInjectorTest {

    private PacketEventsAPI<?> previousApi;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        previousApi = PacketEvents.getAPI();
        // Packet wrappers need a protocol version; no server, network or injector is started.
        PacketEvents.setAPI(new TestPacketEventsApi());
        fixture = new Fixture();
    }

    @AfterEach
    void restoreApi() {
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void serverStopDoesNotSendToTornDownTransportButCleansTasksAndSessions() throws ReflectiveOperationException {
        fixture.stopping = true;
        fixture.injectedUser.missingContext = true;
        fixture.injector.scheduleFallback(fixture.playerId);
        fixture.injector.shutdown();

        assertEquals(0, fixture.playerLookups);
        assertEquals(0, fixture.userLookups);
        assertEquals(0, fixture.injectedUser.attempts);
        assertTrue(fixture.logs.isEmpty());
        assertEquals(1, fixture.cancelledTasks);
        assertTrue(fixture.state("pendingFallbacks").isEmpty());
        assertTrue(fixture.state("injectedPlayers").isEmpty());

        fixture.injector.scheduleFallback(fixture.playerId);
        fixture.fallback.run();
        fixture.stopping = false;
        fixture.injector.shutdown();
        assertEquals(1, fixture.scheduledTasks);
        assertEquals(0, fixture.injectedUser.attempts);
    }

    @Test
    void standaloneDisableRemovesOnlyRedirectTabAndIsIdempotent() {
        fixture.injector.shutdown();
        assertEquals(1, fixture.injectedUser.attempts);
        var packet = fixture.injectedUser.packet;
        assertNotNull(packet);
        assertFalse(packet.isReset());
        assertEquals(Set.of(new ResourceLocation("heavencube", "menu_redirect")), packet.getRemovedAdvancements());
        assertTrue(packet.getAddedAdvancements().isEmpty());
        assertTrue(packet.getProgress().isEmpty());
        fixture.injector.shutdown();
        assertEquals(1, fixture.injectedUser.attempts);
    }

    @Test
    void replacedSessionDoesNotReceiveRemovalForOldInjection() {
        fixture.currentUser = new RecordingUser(fixture.playerId);
        fixture.injector.shutdown();
        assertEquals(0, fixture.injectedUser.attempts);
        assertEquals(0, fixture.currentUser.attempts);
    }

    @Test
    void disconnectedPlayerIsSkippedWithoutConsultingPacketEvents() {
        fixture.online = false;
        fixture.injector.shutdown();
        assertEquals(0, fixture.userLookups);
        assertEquals(0, fixture.injectedUser.attempts);
    }

    @Test
    void unexpectedSendFailureIsReportedAndReferencesAreStillCleared() throws ReflectiveOperationException {
        fixture.injectedUser.missingContext = true;
        fixture.injector.shutdown();
        assertEquals(1, fixture.injectedUser.attempts);
        assertEquals(1, fixture.logs.size());
        assertInstanceOf(NullPointerException.class, fixture.logs.getFirst().getThrown());
        assertTrue(fixture.state("injectedPlayers").isEmpty());
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final RecordingUser injectedUser = new RecordingUser(playerId);
        private RecordingUser currentUser = injectedUser;
        private final List<LogRecord> logs = new ArrayList<>();
        private final AdvancementInjector injector;
        private boolean stopping;
        private boolean online = true;
        private int playerLookups;
        private int userLookups;
        private int scheduledTasks;
        private int cancelledTasks;
        private Runnable fallback;

        private Fixture() {
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { logs.add(record); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "TestPlayer";
                case "isOnline" -> online;
                default -> throw new UnsupportedOperationException(method.toString());
            });
            BukkitTask task = proxy(BukkitTask.class, (object, method, args) -> {
                if (method.getName().equals("cancel")) {
                    cancelledTasks++;
                    return null;
                }
                throw new UnsupportedOperationException(method.toString());
            });
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (object, method, args) -> {
                if (method.getName().equals("runTaskLater")) {
                    scheduledTasks++;
                    fallback = (Runnable) args[1];
                    return task;
                }
                throw new UnsupportedOperationException(method.toString());
            });
            Server server = proxy(Server.class, (object, method, args) -> switch (method.getName()) {
                case "isStopping" -> stopping;
                case "getScheduler" -> scheduler;
                case "getPlayer" -> {
                    playerLookups++;
                    yield player;
                }
                default -> throw new UnsupportedOperationException(method.toString());
            });
            Plugin plugin = proxy(Plugin.class, (object, method, args) -> switch (method.getName()) {
                case "getServer" -> server;
                case "getLogger" -> logger;
                case "isEnabled" -> true;
                default -> throw new UnsupportedOperationException(method.toString());
            });
            PlayerManager manager = proxy(PlayerManager.class, (object, method, args) -> {
                if (method.getName().equals("getUser")) {
                    userLookups++;
                    return currentUser;
                }
                throw new UnsupportedOperationException(method.toString());
            });
            injector = new AdvancementInjector(plugin, manager);
            try {
                this.<User>state("injectedPlayers").put(playerId, injectedUser);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot seed the simulated already-injected session", exception);
            }
        }

        @SuppressWarnings("unchecked")
        private <T> Map<UUID, T> state(String name) throws ReflectiveOperationException {
            // Test setup/inspection only; production exposes no state mutation hooks.
            Field field = AdvancementInjector.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<UUID, T>) field.get(injector);
        }
    }

    private static final class RecordingUser extends User {
        private int attempts;
        private boolean missingContext;
        private WrapperPlayServerUpdateAdvancements packet;

        private RecordingUser(UUID id) {
            super(new Object(), ConnectionState.PLAY, ClientVersion.V_26_3, new UserProfile(id, "TestPlayer"));
        }

        @Override
        public void sendPacketSilently(PacketWrapper<?> wrapper) {
            attempts++;
            if (missingContext) {
                throw new NullPointerException("PacketEvents Netty context already removed");
            }
            packet = assertInstanceOf(WrapperPlayServerUpdateAdvancements.class, wrapper);
        }
    }

    private static final class TestPacketEventsApi extends PacketEventsAPI<Void> {
        private final NettyManager netty = new NettyManagerImpl();
        @Override public boolean isLoaded() { return true; }
        @Override public void init() { throw new UnsupportedOperationException(); }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isTerminated() { return false; }
        @Override public Void getPlugin() { return null; }
        @Override public ServerManager getServerManager() { return () -> ServerVersion.V_26_3; }
        @Override public ProtocolManager getProtocolManager() { throw new UnsupportedOperationException(); }
        @Override public PlayerManager getPlayerManager() { throw new UnsupportedOperationException(); }
        @Override public NettyManager getNettyManager() { return netty; }
        @Override public ChannelInjector getInjector() { throw new UnsupportedOperationException(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
