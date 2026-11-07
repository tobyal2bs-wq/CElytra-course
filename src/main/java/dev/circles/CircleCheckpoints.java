package dev.circles;

import com.github.retrooper.packetevents.PacketEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class CircleCheckpoints extends JavaPlugin implements Listener {
    public record RunView(String course, String layout, int checkpoint, int total, long startNanos) {
        public long elapsed() { return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000); }
    }
    private record Hit(Course course, int number, double fraction) {}
    private record Candidate(String key, Ring ring, double distance) {}
    /** All race/UI fields belong exclusively to this player's entity scheduler. */
    final class Session {
        final Player player;
        final Map<String, Packets.Visual> visuals = new ConcurrentHashMap<>();
        ScheduledTask task;
        Race race;
        Course finishedCourse;
        long finishedMillis, finishUntil, warningUntil, lastStart;
        String warning;
        int visualTicks;
        Session(Player player) { this.player = player; }
        void clearVisuals() {
            visuals.values().forEach(v -> packets.destroy(player, v)); visuals.clear();
        }
    }
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RunView> runViews = new ConcurrentHashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();
    private Store store;
    private Packets packets;
    private CirclesExpansion expansion;
    private ElytraExpansion elytraExpansion;
    private boolean circlesRegistered, elytraRegistered;
    private volatile String leaderboardCourse;
    private MarkerEditor editor;
    private RaceEffects effects;
    private int orbs, visible, refreshTicks, uiTicks, editorTicks;
    private double viewDistanceSquared, radius, bandWidth, maxMoveSquared;
    private long finishDuration, maxRunMillis;
    private boolean sidebarEnabled, flight, elytra, vehicles;
    private Component sidebarTitle;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            orbs = integer("rings.orbs-per-ring", 64, 2048);
            bandWidth = number("rings.band-width", .05, 4);
            int configVersion = getConfig().contains("config-version", true) ? getConfig().getInt("config-version") : 1;
            // Upgrade old defaults in memory; leave custom settings and the file on disk intact.
            if (configVersion < 3) {
                if (orbs == 128 || orbs == 640) orbs = 960;
                if (Math.abs(bandWidth-.7) < 1e-9) bandWidth = .45;
            }
            editorTicks = integer("editor.poll-ticks", 1, 20);
            visible = integer("rings.max-visible-rings", 1, 64);
            refreshTicks = integer("rings.refresh-ticks", 2, 100);
            uiTicks = integer("race.ui-update-ticks", 1, 20);
            radius = number("rings.radius", 1, 16);
            double range = number("rings.view-distance", 8, 96); viewDistanceSquared = range * range;
            double move = number("race.max-move-blocks", 1, 128); maxMoveSquared = move * move;
            finishDuration = integer("race.finish-display-seconds", 1, 60) * 1_000_000_000L;
            maxRunMillis = integer("race.max-run-minutes", 1, 1440) * 60_000L;
            String timerLocation = getConfig().getString("race.timer-location", "actionbar").toLowerCase(Locale.ROOT);
            if (!List.of("actionbar", "both").contains(timerLocation)) throw new IllegalArgumentException("race.timer-location must be actionbar or both");
            sidebarEnabled = timerLocation.equals("both") && getConfig().getBoolean("sidebar.enabled", true);
            leaderboardCourse = getConfig().getString("placeholders.elytra-course", "auto").toLowerCase(Locale.ROOT);
            if (!leaderboardCourse.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("Invalid placeholders.elytra-course");
            sidebarTitle = mm.deserialize(getConfig().getString("sidebar.title", "<gold>CIRCLES"));
            flight = getConfig().getBoolean("race.allow-flight");
            elytra = getConfig().getBoolean("race.allow-elytra");
            vehicles = getConfig().getBoolean("race.allow-vehicles");
            var dependency = getServer().getPluginManager().getPlugin("packetevents");
            if (dependency == null || compareVersion(dependency.getPluginMeta().getVersion(), 2, 14, 0) < 0)
                throw new IllegalStateException("Install PacketEvents 2.14.0 or newer");
            store = new Store(getDataFolder().toPath().resolve("state.yml"),
                    ex -> getLogger().log(Level.SEVERE, "Could not save course/results data", ex));
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "CircleCheckpoints could not start. Existing data was preserved.", ex);
            getServer().getPluginManager().disablePlugin(this); return;
        }
        packets = new Packets();
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        getServer().getPluginManager().registerEvents(this, this);
        editor = new MarkerEditor(this, editorTicks);
        getServer().getPluginManager().registerEvents(editor, this);
        effects = new RaceEffects(this);
        getServer().getPluginManager().registerEvents(effects, this);
        CirclesCommand handler = new CirclesCommand(this);
        Objects.requireNonNull(getCommand("circles")).setExecutor(handler);
        Objects.requireNonNull(getCommand("circles")).setTabCompleter(handler);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new CirclesExpansion(this);
            circlesRegistered = expansion.register();
            if (!circlesRegistered) getLogger().warning("Could not register circles placeholders; identifier already in use.");
            elytraExpansion = new ElytraExpansion(this);
            elytraRegistered = elytraExpansion.register();
            if (!elytraRegistered) getLogger().warning("Could not register elytra placeholders; another expansion owns elytra. Existing circles placeholders remain available.");
        }
        getServer().getOnlinePlayers().forEach(this::attach);
        getLogger().info("CircleCheckpoints 1.2.1 enabled for Paper-compatible servers / Folia 26.2. Courses: " + store.courses().size());
    }
    private int integer(String key, int min, int max) {
        int value = getConfig().getInt(key);
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }
    private double number(String key, double min, double max) {
        double value = getConfig().getDouble(key);
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }
    static int compareVersion(String text, int a, int b, int c) {
        String[] parts = text.replaceFirst("^[^0-9]*", "").split("[.\\-+]");
        int[] wanted = {a, b, c};
        for (int i = 0; i < 3; i++) {
            int actual;
            try { actual = i < parts.length ? Integer.parseInt(parts[i]) : 0; }
            catch (NumberFormatException ex) { return -1; }
            if (actual != wanted[i]) return Integer.compare(actual, wanted[i]);
        }
        return 0;
    }
    @Override public void onDisable() {
        if (editor != null) editor.close();
        if (circlesRegistered) expansion.unregister();
        if (elytraRegistered) elytraExpansion.unregister();
        if (packets != null) {
            sessions.values().forEach(s -> {
                if (s.task != null) s.task.cancel();
                // Packet-only cleanup is thread-independent; never access a remote region's world/entities.
                s.clearVisuals(); packets.hideSidebar(s.player);
            });
            PacketEvents.getAPI().getEventManager().unregisterListener(packets);
        }
        sessions.clear(); runViews.clear();
        if (store != null) store.close();
    }
    private void attach(Player p) {
        p.getScheduler().run(this, task -> {
            Session s = new Session(p);
            if (sessions.putIfAbsent(p.getUniqueId(), s) != null) return;
            s.task = p.getScheduler().runAtFixedRate(this, tick -> update(s),
                    () -> { sessions.remove(p.getUniqueId(), s); runViews.remove(p.getUniqueId()); packets.forget(p.getUniqueId()); },
                    1, uiTicks);
        }, null);
    }
    @EventHandler public void join(PlayerJoinEvent e) { attach(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId(); Session s = sessions.remove(uuid);
        if (s != null && s.task != null) s.task.cancel();
        runViews.remove(uuid); packets.forget(uuid);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) { cancel(s, "Run cancelled: you teleported."); s.clearVisuals(); }
    }
    @EventHandler public void died(PlayerDeathEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) cancel(s, "Run cancelled: you died.");
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) { cancel(s, "Run cancelled: you respawned."); s.clearVisuals(); }
    }
    @EventHandler public void worldChanged(PlayerChangedWorldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) { cancel(s, "Run cancelled: world changed."); s.clearVisuals(); }
    }
    private String ineligible(Player p) {
        if (!p.hasPermission("circles.use")) return "You do not have permission to run courses.";
        if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) return "You cannot race in this state.";
        if (!flight && p.isFlying()) return "Flying is disabled for timed runs.";
        if (!elytra && p.isGliding()) return "Elytra are disabled for timed runs.";
        if (!vehicles && p.isInsideVehicle()) return "Vehicles are disabled for timed runs.";
        return null;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent) return;
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || e.getTo() == null || !e.getFrom().getWorld().equals(e.getTo().getWorld())) return;
        Vec from = torso(e.getFrom()), to = torso(e.getTo());
        double distance = to.sub(from).lengthSquared();
        if (distance < 1e-12) return;
        if (distance > maxMoveSquared) { cancel(s, "Run cancelled: movement was too large."); return; }
        String reason = ineligible(s.player);
        if (reason != null) { if (s.race != null) cancel(s, reason); return; }
        long now = System.nanoTime();
        if (!validLayout(s)) cancel(s, "Run cancelled: the course was edited or disabled.");
        List<Hit> hits = new ArrayList<>();
        UUID world = e.getTo().getWorld().getUID();
        Collection<Course> courses = s.race == null ? store.courses().values() : List.of(s.race.course());
        for (Course c : courses) {
            if (!c.enabled()) continue;
            // Before a run starts, include later hits too so a fast segment can cross start and checkpoint 2.
            for (int i = 0; i < c.rings().size(); i++) {
                Ring r = c.rings().get(i);
                if (!world.equals(r.world())) continue;
                var crossing = r.crossing(from, to, r.radius() - r.effectiveWidth(bandWidth));
                if (crossing.isPresent()) hits.add(new Hit(c, i + 1, crossing.getAsDouble()));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::fraction).thenComparing(h -> h.course().id()).thenComparingInt(Hit::number));
        for (Hit hit : hits) {
            if (s.race == null) {
                if (hit.number() == 1 && (s.lastStart == 0 || now - s.lastStart > 1_000_000_000L)) start(s, hit.course(), now);
                continue;
            }
            if (!s.race.course().resultKey().equals(hit.course().resultKey())) continue;
            if (hit.number() == 1) {
                if (now - s.lastStart > 1_000_000_000L) start(s, hit.course(), now);
                continue;
            }
            Race.Outcome outcome = s.race.cross(hit.number());
            switch (outcome) {
                case SKIPPED -> {
                    warn(s, "Checkpoint skipped! Pass " + (s.race.passed() + 1) + " first.", now);
                    s.player.sendMessage(text("<red>Checkpoint skipped!</red> <yellow>Pass checkpoint <needed> first.", "needed", "" + (s.race.passed() + 1)));
                }
                case CHECKPOINT -> {
                    s.warningUntil = 0;
                    RaceEffects.checkpoint(s.player);
                    publish(s);
                }
                case DONE -> finish(s, now);
                case OLD -> { }
            }
        }
    }
    static Vec torso(Location l) { return new Vec(l.getX(), l.getY() + .9, l.getZ()); }
    private boolean validLayout(Session s) {
        if (s.race == null) return true;
        Course current = store.courses().get(s.race.course().id());
        return current != null && current.enabled() && current.layout().equals(s.race.course().layout());
    }
    private void start(Session s, Course c, long now) {
        s.race = new Race(c, now); s.lastStart = now; s.warningUntil = 0; s.finishedCourse = null;
        s.player.sendMessage(text("<green>Started <course>!</green> <gray>Pass every ring in order.", "course", c.id()));
        RaceEffects.checkpoint(s.player);
        publish(s);
    }
    private void publish(Session s) {
        if (s.race == null) { runViews.remove(s.player.getUniqueId()); return; }
        Race r = s.race;
        runViews.put(s.player.getUniqueId(), new RunView(r.course().id(), r.course().layout(), r.passed(), r.course().rings().size(), r.startNanos()));
    }
    private void finish(Session s, long now) {
        Course c = s.race.course(); long elapsed = s.race.elapsedMillis(now);
        boolean pb = store.record(c, s.player.getUniqueId(), s.player.getName(), elapsed);
        s.finishedCourse = c; s.finishedMillis = elapsed; s.finishUntil = now + finishDuration;
        s.race = null; s.warningUntil = 0; publish(s);
        s.player.sendMessage(text("<green><bold>Done!</bold></green> <course>: <gold><time></gold><pb>",
                "course", c.id(), "time", Times.format(elapsed), "pb", pb ? " - New personal best!" : ""));
        s.player.showTitle(net.kyori.adventure.title.Title.title(Component.text("Done!"), Component.text(Times.format(elapsed)),
                net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(3), Duration.ofMillis(500))));
        RaceEffects.checkpoint(s.player);
        effects.finish(s.player);
    }
    private void warn(Session s, String message, long now) { s.warning = message; s.warningUntil = now + 3_000_000_000L; }
    private void cancel(Session s, String message) {
        boolean running = s.race != null;
        s.race = null; s.finishedCourse = null; publish(s);
        if (running) { s.player.sendMessage(Component.text(message)); warn(s, message, System.nanoTime()); }
        packets.hideSidebar(s.player);
    }
    public void leave(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) {
            boolean active = s.race != null;
            cancel(s, "Run cancelled. Pass ring 1 to start again.");
            if (!active) player.sendMessage(Component.text("Pass ring 1 to start a course."));
        }
        else player.sendMessage(Component.text("Pass ring 1 to start a course."));
    }
    private void update(Session s) {
        Player p = s.player; long now = System.nanoTime();
        if (!validLayout(s)) cancel(s, "Run cancelled: the course was edited or disabled.");
        if (s.race != null) {
            String reason = ineligible(p);
            if (reason != null) cancel(s, reason);
            else if (s.race.elapsedMillis(now) >= maxRunMillis) cancel(s, "Run cancelled: time limit reached.");
        }
        if ((s.visualTicks -= uiTicks) <= 0) { s.visualTicks = refreshTicks; render(s); }
        if (s.race != null) {
            Race r = s.race;
            p.sendActionBar(RaceHud.running(r.passed(), r.course().rings().size(), r.elapsedMillis(now),
                    s.warningUntil > now ? s.warning : null));
            showSidebar(s, r.course(), r.passed(), r.elapsedMillis(now), false);
        } else if (s.finishedCourse != null && now < s.finishUntil) {
            p.sendActionBar(text("<green>Done!</green> <gold><time></gold>", "time", Times.format(s.finishedMillis)));
            showSidebar(s, s.finishedCourse, s.finishedCourse.rings().size(), s.finishedMillis, true);
        } else {
            if (s.finishedCourse != null) { s.finishedCourse = null; packets.hideSidebar(p); p.sendActionBar(Component.empty()); }
            if (s.warningUntil > now) p.sendActionBar(Component.text(s.warning));
        }
    }
    private void showSidebar(Session s, Course c, int done, long time, boolean finished) {
        if (!sidebarEnabled) return;
        Store.Result personal = store.result(c, s.player.getUniqueId());
        packets.sidebar(s.player, sidebarTitle, List.of(
                text("<yellow><course>", "course", c.id()),
                text("<white>Checkpoint: <done>/<total>", "done", "" + done, "total", "" + c.rings().size()),
                text("<white>Time: <gold><time>", "time", Times.format(time)),
                text("<gray>Best: <best>", "best", Times.format(personal == null ? -1 : personal.best())),
                mm.deserialize(finished ? "<green>Done!" : "<green>Running...")));
    }
    private void render(Session s) {
        Location loc = s.player.getLocation();
        Vec position = new Vec(loc.getX(), loc.getY(), loc.getZ());
        UUID world = loc.getWorld().getUID();
        List<Candidate> candidates = new ArrayList<>();
        boolean admin = s.player.hasPermission("circles.admin");
        for (Course c : store.courses().values()) {
            if (!c.enabled() && !admin) continue;
            for (int i = 0; i < c.rings().size(); i++) {
                Ring r = c.rings().get(i);
                if (!r.world().equals(world)) continue;
                double distance = r.center().sub(position).lengthSquared();
                if (distance <= viewDistanceSquared) candidates.add(new Candidate(c.resultKey() + ":" + i, r, distance));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(Candidate::key));
        Set<String> keep = new HashSet<>();
        for (Candidate candidate : candidates.stream().limit(visible).toList()) keep.add(candidate.key());
        s.visuals.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            packets.destroy(s.player, e.getValue()); return true;
        });
        for (Candidate candidate : candidates.stream().limit(visible).toList()) {
            Packets.Visual v = s.visuals.get(candidate.key());
            if (v == null) s.visuals.put(candidate.key(), packets.spawn(s.player, candidate.ring(), orbs, bandWidth));
            else s.visuals.put(candidate.key(), packets.move(s.player, v, candidate.ring()));
        }
    }
    Component text(String template, String... pairs) {
        var tags = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[pairs.length / 2];
        for (int i = 0; i < tags.length; i++) tags[i] = Placeholder.unparsed(pairs[i * 2], pairs[i * 2 + 1]);
        return mm.deserialize(template, tags);
    }
    public Store store() { return store; }
    public RunView view(UUID uuid) { return runViews.get(uuid); }
    String leaderboardCourse() { return leaderboardCourse; }
    void leaderboardCourse(String id) {
        if (!store.courses().containsKey(id)) throw new IllegalArgumentException("Unknown course: " + id);
        getConfig().set("placeholders.elytra-course", id); saveConfig(); leaderboardCourse = id;
    }
    MarkerEditor editor() { return editor; }
    double defaultRadius() { return radius; }
}
