package dev.circles;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** All entity reads/removals happen on that entity's owner; creation uses the target region. */
public final class MarkerEditor implements Listener, AutoCloseable {
    private static final class Edit {
        final String course;
        final EditDraft draft = new EditDraft();
        final ConcurrentMap<Integer, Handle> handles = new ConcurrentHashMap<>();
        final Set<Integer> pending = ConcurrentHashMap.newKeySet();
        volatile boolean active = true;
        volatile boolean finishing;
        Edit(String course) { this.course = course; }
    }
    private static final class Handle {
        final ItemDisplay entity;
        final UUID uuid;
        volatile ScheduledTask task;
        Ring accepted;
        volatile boolean unsaved;
        long lastMoved;
        Handle(ItemDisplay entity, Ring ring) { this.entity = entity; this.uuid = entity.getUniqueId(); accepted = ring; }
    }
    private final CircleCheckpoints plugin;
    private final NamespacedKey tag;
    private final int pollTicks;
    private final ConcurrentMap<String, Edit> edits = new ConcurrentHashMap<>();
    private volatile boolean closed;
    MarkerEditor(CircleCheckpoints plugin, int pollTicks) {
        this.plugin = plugin; this.pollTicks = pollTicks; this.tag = new NamespacedKey(plugin, "editor_handle");
    }
    public boolean editing(String course) { return edits.containsKey(course); }
    public void start(String courseId, Integer number, Player admin) {
        Course course;
        synchronized (plugin.store()) {
            course = plugin.store().courses().get(courseId);
            if (course == null) throw new IllegalArgumentException("Unknown course: " + courseId);
            if (course.rings().isEmpty()) throw new IllegalArgumentException("Set at least one ring first.");
            if (number != null && (number < 1 || number > course.rings().size())) throw new IllegalArgumentException("That checkpoint does not exist.");
            course = course.enabled(false); plugin.store().put(course);
        }
        Edit edit = edits.computeIfAbsent(courseId, Edit::new);
        if (edit.finishing) throw new IllegalArgumentException("This course is finishing its edit; wait a moment.");
        for (int i = 0; i < course.rings().size(); i++) if (number == null || number == i + 1) {
            int index = i; Ring ring = course.rings().get(i);
            if (edit.handles.containsKey(index) || !edit.pending.add(index)) continue;
            World world = plugin.getServer().getWorld(ring.world());
            if (world == null) { edit.pending.remove(index); tell(admin, "World for ring " + (index+1) + " is not loaded."); continue; }
            Location at = new Location(world, ring.center().x(), ring.center().y(), ring.center().z());
            world.getChunkAtAsync(at).whenComplete((chunk, error) -> {
                if (error != null) { edit.pending.remove(index); tell(admin, "Could not load ring " + (index+1) + "'s chunk."); return; }
                if (closed || !plugin.isEnabled() || !edit.active) { edit.pending.remove(index); return; }
                plugin.getServer().getRegionScheduler().run(plugin, at, task -> spawn(edit, index, ring, at));
            });
        }
    }
    private void spawn(Edit edit, int index, Ring ring, Location at) {
        try {
            synchronized (plugin.store()) {
                Course current = plugin.store().courses().get(edit.course);
                if (closed || !edit.active || edit.finishing || current == null || current.enabled() || index >= current.rings().size()
                        || !ring.near(current.rings().get(index))) return;
                ItemDisplay entity = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                    d.setPersistent(false); d.setGravity(false); d.setInvulnerable(true);
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setItemStack(new ItemStack(Material.EMERALD_BLOCK));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    d.setTransformation(MarkerPose.initial(ring));
                    d.setInterpolationDuration(0); d.setTeleportDuration(0);
                    d.customName(Component.text("Circle " + edit.course + " #" + (index+1)));
                    d.setCustomNameVisible(true);
                    d.getPersistentDataContainer().set(tag, PersistentDataType.STRING, edit.course + ":" + index);
                    d.addScoreboardTag("circlecheckpoints_editor");
                });
                Handle handle = new Handle(entity, ring);
                Handle old = edit.handles.putIfAbsent(index, handle);
                if (old != null) { entity.remove(); return; }
                handle.task = entity.getScheduler().runAtFixedRate(plugin, task -> poll(edit, index, handle),
                        () -> { edit.handles.remove(index, handle); if (handle.unsaved && !closed) plugin.store().flush(); }, 1, pollTicks);
            }
        } finally { edit.pending.remove(index); }
    }
    private void poll(Edit edit, int index, Handle handle) {
        ItemDisplay entity = handle.entity;
        if (!edit.active || closed || !entity.isValid()) { removeOwned(edit, index, handle); return; }
        try {
            entity.setPersistent(false);
            Location at = entity.getLocation();
            Ring pose = MarkerPose.read(at.getWorld().getUID(), new Vec(at.getX(), at.getY(), at.getZ()),
                    at.getYaw(), at.getPitch(), entity.getTransformation(), handle.accepted.radius());
            long now = System.nanoTime();
            synchronized (plugin.store()) {
                Course current = plugin.store().courses().get(edit.course);
                if (current == null || current.enabled() || !edit.active) { removeOwned(edit, index, handle); return; }
                // A course stays single-world; a handle teleported to another world is not committed.
                if (!pose.world().equals(handle.accepted.world())) { removeOwned(edit, index, handle); return; }
                Course updated = edit.draft.move(current, index, handle.accepted, pose);
                if (updated == null) { removeOwned(edit, index, handle); return; }
                if (updated != current) {
                    plugin.store().putTransient(updated); handle.accepted = pose; handle.unsaved = true; handle.lastMoved = now;
                }
                if (handle.unsaved && now-handle.lastMoved >= 500_000_000L) {
                    plugin.store().flush(); handle.unsaved = false;
                }
            }
        } catch (IllegalArgumentException ex) {
            // Invalid intermediate editor transforms leave the last valid geometry intact.
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Stopped editor handle for " + edit.course + " #" + (index+1), ex);
            removeOwned(edit, index, handle);
        }
    }
    private void removeOwned(Edit edit, int index, Handle handle) {
        edit.handles.remove(index, handle);
        if (handle.task != null) handle.task.cancel();
        if (handle.entity.isValid()) handle.entity.remove();
        if (handle.unsaved && !closed) plugin.store().flush();
    }
    public void stop(String course) {
        Edit edit = edits.remove(course);
        if (edit == null) return;
        edit.active = false;
        for (var e : edit.handles.entrySet()) {
            Handle handle = e.getValue(); int index = e.getKey();
            if (Bukkit.isOwnedByCurrentRegion(handle.entity)) removeOwned(edit, index, handle);
            else if (plugin.isEnabled()) handle.entity.getScheduler().run(plugin, t -> removeOwned(edit, index, handle), null);
            if (handle.task != null) handle.task.cancel();
        }
        plugin.store().flush();
    }
    /** Capture the very last gizmo update on each owning region before enabling the course. */
    public CompletableFuture<Void> finishAndEnable(String course) {
        Edit edit = edits.get(course);
        if (edit == null) {
            synchronized (plugin.store()) {
                Course c = plugin.store().courses().get(course);
                if (c == null) throw new IllegalArgumentException("Unknown course: " + course);
                plugin.store().put(c.enabled(true));
            }
            return CompletableFuture.completedFuture(null);
        }
        if (edit.finishing) throw new IllegalArgumentException("This course is already finishing its edit.");
        edit.finishing = true;
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        for (var entry : edit.handles.entrySet()) {
            int index = entry.getKey(); Handle handle = entry.getValue();
            CompletableFuture<Void> read = new CompletableFuture<>(); reads.add(read);
            var scheduled = handle.entity.getScheduler().run(plugin, task -> {
                try { poll(edit, index, handle); removeOwned(edit, index, handle); read.complete(null); }
                catch (Exception ex) { read.completeExceptionally(ex); }
            }, () -> read.complete(null));
            if (scheduled == null) read.complete(null);
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenRun(() -> {
            synchronized (plugin.store()) {
                if (closed || !edit.active || edits.get(course) != edit) throw new IllegalArgumentException("Edit was cancelled; course stays disabled.");
                Course c = plugin.store().courses().get(course);
                if (c == null) throw new IllegalArgumentException("Course was deleted.");
                edit.active = false; edits.remove(course, edit);
                plugin.store().put(c.enabled(true));
            }
        });
    }
    private void tell(Player player, String message) {
        if (plugin.isEnabled()) player.getScheduler().run(plugin, task -> player.sendMessage(Component.text(message)), null);
    }
    @EventHandler public void loaded(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (!(entity instanceof ItemDisplay) || !entity.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) continue;
            // Clean handles copied/saved by an external editor. A loaded copy is not a new checkpoint.
            boolean active = edits.values().stream().flatMap(edit -> edit.handles.values().stream())
                    .anyMatch(h -> h.uuid.equals(entity.getUniqueId()));
            if (!active) entity.remove();
        }
    }
    @Override public void close() {
        closed = true;
        for (String course : List.copyOf(edits.keySet())) stop(course);
        // On Folia shutdown, remote-region entities cannot be removed from the global thread.
        // Handles are non-persistent and therefore never written into saved chunks by this plugin.
    }
}
