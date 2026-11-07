package dev.circles;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.*;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Packet construction/mapping tests, not a substitute for client rendering or network integration. */
class PacketsTest {
    private PacketEventsAPI<?> original;
    @BeforeEach void api() {
        original = PacketEvents.getAPI();
        PacketEvents.setAPI(new PacketEventsAPI<Object>() {
            public boolean isLoaded() { return true; }
            public void init() {}
            public boolean isInitialized() { return true; }
            public boolean isTerminated() { return false; }
            public Object getPlugin() { return null; }
            public ServerManager getServerManager() { return () -> ServerVersion.V_26_2; }
            public ProtocolManager getProtocolManager() { return null; }
            public PlayerManager getPlayerManager() { return null; }
            public NettyManager getNettyManager() { return null; }
            public ChannelInjector getInjector() { return null; }
        });
    }
    @AfterEach void restore() { PacketEvents.setAPI(original); }
    private Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, args) ->
                switch (m.getName()) {
                    case "getUniqueId" -> uuid;
                    case "toString" -> "PacketTestPlayer";
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> p == args[0];
                    default -> throw new AssertionError("Unexpected Bukkit operation: " + m.getName());
                });
    }
    @Test void usesModernMappedOrbPacketsWithNoGravityAndXpValue() {
        List<PacketWrapper<?>> sent = new ArrayList<>();
        Packets packets = new Packets((p, packet) -> sent.add(packet));
        Player p = player(UUID.randomUUID()); Ring r = RaceTest.course("spawn", 3).rings().getFirst();
        Packets.Visual visual = packets.spawn(p, r, 128);
        assertEquals(256, sent.size()); assertEquals(128, Arrays.stream(visual.ids()).distinct().count());
        for (int i = 0; i < 128; i++) {
            var spawn = assertInstanceOf(WrapperPlayServerSpawnEntity.class, sent.get(i * 2));
            assertEquals(EntityTypes.EXPERIENCE_ORB, spawn.getEntityType());
            assertEquals(PacketType.Play.Server.SPAWN_ENTITY.getId(ClientVersion.V_26_2), spawn.getPacketId());
            var metadata = assertInstanceOf(WrapperPlayServerEntityMetadata.class, sent.get(i * 2 + 1));
            assertEquals(true, metadata.getEntityMetadata().get(0).getValue());
            assertEquals(5, metadata.getEntityMetadata().get(0).getIndex());
            assertEquals(1, metadata.getEntityMetadata().get(1).getValue());
            assertEquals(8, metadata.getEntityMetadata().get(1).getIndex());
        }
        assertTrue(PacketType.Play.Server.SPAWN_EXPERIENCE_ORB.getId(ClientVersion.V_26_2) < 0,
                "Legacy experience-orb packet must not accidentally be used for 26.2");
        sent.clear(); packets.pin(p, visual); assertEquals(128, sent.size());
        assertInstanceOf(WrapperPlayServerEntityTeleport.class, sent.getFirst());
        sent.clear(); packets.destroy(p, visual);
        assertArrayEquals(visual.ids(), assertInstanceOf(WrapperPlayServerDestroyEntities.class, sent.getFirst()).getEntityIds());
    }
    @Test void sidebarIsCreatedOnceUpdatesRowsAndIsRemovedAtEnd() {
        List<PacketWrapper<?>> sent = new ArrayList<>(); Packets packets = new Packets((p, packet) -> sent.add(packet));
        Player p = player(UUID.randomUUID()); List<Component> lines = List.of(Component.text("Time: 01:21.011"));
        packets.sidebar(p, Component.text("CIRCLES"), lines);
        assertEquals(3, sent.size());
        assertInstanceOf(WrapperPlayServerScoreboardObjective.class, sent.get(0));
        assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, sent.get(1));
        assertEquals(lines.getFirst(), assertInstanceOf(WrapperPlayServerUpdateScore.class, sent.get(2)).getEntityDisplayName());
        sent.clear(); packets.sidebar(p, Component.text("CIRCLES"), lines);
        assertEquals(1, sent.size()); assertInstanceOf(WrapperPlayServerUpdateScore.class, sent.getFirst());
        sent.clear(); packets.hideSidebar(p);
        assertEquals(2, sent.size());
        assertEquals(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                assertInstanceOf(WrapperPlayServerScoreboardObjective.class, sent.getFirst()).getMode());
        sent.clear(); packets.hideSidebar(p); assertTrue(sent.isEmpty());
    }
    @Test void movingAndTiltingAHandleMovesExistingOrbsWithoutRespawningTheirIds() {
        List<PacketWrapper<?>> sent = new ArrayList<>(); Packets packets = new Packets((p, packet) -> sent.add(packet));
        Player player = player(UUID.randomUUID()); Ring original = RaceTest.course("spawn", 3).rings().getFirst();
        Packets.Visual before = packets.spawn(player, original, 640, .7); sent.clear();
        Ring moved = new Ring(original.world(), original.center().add(new Vec(5, 9, -3)), Rotation.degrees(27.5, 90, 18.2), 3.5);
        Packets.Visual after = packets.move(player, before, moved);
        assertSame(before.ids(), after.ids()); assertEquals(moved, after.ring()); assertEquals(640, sent.size());
        for (int i = 0; i < sent.size(); i++) {
            var teleport = assertInstanceOf(WrapperPlayServerEntityTeleport.class, sent.get(i));
            assertEquals(before.ids()[i], teleport.getEntityId());
            Vec expected = moved.orbPoint(i, 640, .7);
            assertEquals(expected.x(), teleport.getPosition().x, 1e-9);
            assertEquals(expected.y(), teleport.getPosition().y, 1e-9);
            assertEquals(expected.z(), teleport.getPosition().z, 1e-9);
        }
    }
}
