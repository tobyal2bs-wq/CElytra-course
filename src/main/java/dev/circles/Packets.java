package dev.circles;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.entity.data.*;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.score.BlankScoreFormat;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Client-only entities and sidebar: no global Bukkit scoreboard or world entity access. */
public final class Packets extends PacketListenerAbstract {
    private static final String OBJECTIVE = "circles_timer";
    private final AtomicInteger ids = new AtomicInteger(-2_000_000_000);
    private final Set<UUID> sidebars = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, String> previousSidebar = new ConcurrentHashMap<>();
    private final BiConsumer<Player, PacketWrapper<?>> transport;
    public record Visual(Ring ring, int[] ids, double bandWidth) {}

    public Packets() { this((p, packet) -> PacketEvents.getAPI().getPlayerManager().sendPacket(p, packet)); }
    Packets(BiConsumer<Player, PacketWrapper<?>> transport) { super(PacketListenerPriority.HIGHEST); this.transport = transport; }
    private void send(Player p, PacketWrapper<?> packet) { transport.accept(p, packet); }
    public Visual spawn(Player p, Ring ring, int count) {
        return spawn(p, ring, count, .45);
    }
    public Visual spawn(Player p, Ring ring, int count, double bandWidth) {
        int[] allocated = new int[count];
        for (int i = 0; i < count; i++) {
            int id = ids.getAndIncrement(); allocated[i] = id;
            Vec at = ring.orbPoint(i, count, bandWidth);
            send(p, new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.EXPERIENCE_ORB,
                    new Vector3d(at.x(), at.y(), at.z()), 0, 0, 0, 0, Optional.of(new Vector3d(0, 0, 0))));
            // Entity base metadata index 5 is no-gravity on the supported 26.2 protocol.
            send(p, new WrapperPlayServerEntityMetadata(id, List.of(
                    new EntityData<>(5, EntityDataTypes.BOOLEAN, true), new EntityData<>(8, EntityDataTypes.INT, 1))));
        }
        return new Visual(ring, allocated, bandWidth);
    }
    public void pin(Player p, Visual v) {
        move(p, v, v.ring());
    }
    public Visual move(Player p, Visual v, Ring ring) {
        for (int i = 0; i < v.ids().length; i++) {
            Vec at = ring.orbPoint(i, v.ids().length, v.bandWidth());
            send(p, new WrapperPlayServerEntityTeleport(v.ids()[i], new Vector3d(at.x(), at.y(), at.z()), 0, 0, false));
        }
        return new Visual(ring, v.ids(), v.bandWidth());
    }
    public void destroy(Player p, Visual v) { send(p, new WrapperPlayServerDestroyEntities(v.ids())); }
    public void sidebar(Player p, Component title, List<Component> lines) {
        if (sidebars.add(p.getUniqueId())) {
            send(p, new WrapperPlayServerScoreboardObjective(OBJECTIVE,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title,
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER, BlankScoreFormat.INSTANCE));
            send(p, new WrapperPlayServerDisplayScoreboard(1, OBJECTIVE));
        }
        for (int i = 0; i < lines.size(); i++) send(p, new WrapperPlayServerUpdateScore(
                "circles_line_" + i, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                OBJECTIVE, lines.size() - i, lines.get(i), BlankScoreFormat.INSTANCE));
    }
    public void hideSidebar(Player p) {
        if (!sidebars.remove(p.getUniqueId())) return;
        send(p, new WrapperPlayServerScoreboardObjective(OBJECTIVE,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, Component.empty(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
        send(p, new WrapperPlayServerDisplayScoreboard(1, previousSidebar.getOrDefault(p.getUniqueId(), "")));
    }
    public void forget(UUID uuid) { sidebars.remove(uuid); previousSidebar.remove(uuid); }
    @Override public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.DISPLAY_SCOREBOARD || event.getUser() == null) return;
        WrapperPlayServerDisplayScoreboard packet = new WrapperPlayServerDisplayScoreboard(event);
        if (packet.getPosition() != 1 || OBJECTIVE.equals(packet.getScoreName())) return;
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) return;
        previousSidebar.put(uuid, packet.getScoreName());
        if (sidebars.contains(uuid)) event.setCancelled(true);
    }
}
