package dev.circles;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Immutable snapshots cross threads; a single writer atomically replaces the state file. */
public final class Store implements AutoCloseable {
    public record Result(UUID uuid, String name, long best, long last) {}
    private record Snapshot(Map<String, Course> courses, Map<String, Map<UUID, Result>> results) {}
    private final Path file;
    private final Consumer<Throwable> error;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CircleCheckpoints-save"); t.setDaemon(true); return t;
    });
    private final AtomicReference<Snapshot> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile Map<String, Course> courses = Map.of();
    private Map<String, Map<UUID, Result>> results = Map.of();
    private final Map<String, List<Result>> topCache = new HashMap<>();

    public Store(Path file, Consumer<Throwable> error) throws Exception {
        this.file = file; this.error = error;
        if (!Files.exists(file)) return;
        YamlConfiguration y = new YamlConfiguration();
        y.load(file.toFile()); // Deliberately strict: never replace a malformed state file with empty data.
        int schema = y.getInt("schema", -1);
        if (schema != 1 && schema != 2) throw new IOException("Unsupported or missing state schema (expected 1 or 2)");
        Map<String, Course> loaded = new TreeMap<>();
        ConfigurationSection cs = y.getConfigurationSection("courses");
        if (cs != null) for (String id : cs.getKeys(false)) {
            ConfigurationSection c = Objects.requireNonNull(cs.getConfigurationSection(id));
            List<Ring> rings = new ArrayList<>();
            ConfigurationSection rs = c.getConfigurationSection("rings");
            if (rs != null) {
                int count = rs.getKeys(false).size();
                for (int i = 1; i <= count; i++) {
                    ConfigurationSection r = Objects.requireNonNull(rs.getConfigurationSection("" + i), "Checkpoint gap in " + id);
                    rings.add(new Ring(UUID.fromString(r.getString("world", "")),
                            new Vec(r.getDouble("x"), r.getDouble("y"), r.getDouble("z")),
                            schema == 1 ? Rotation.degrees(r.getDouble("yaw"), 0, 0)
                                    : new Rotation(r.getDouble("rotation.x"), r.getDouble("rotation.y"),
                                    r.getDouble("rotation.z"), r.getDouble("rotation.w")), r.getDouble("radius")));
                }
            }
            Course course = new Course(id, c.getString("layout", ""), false, rings).enabled(c.getBoolean("enabled"));
            loaded.put(id, course);
        }
        courses = Map.copyOf(loaded);
        Map<String, Map<UUID, Result>> all = new HashMap<>();
        ConfigurationSection scores = y.getConfigurationSection("results");
        if (scores != null) for (String key : scores.getKeys(false)) {
            Map<UUID, Result> entries = new HashMap<>();
            ConfigurationSection list = Objects.requireNonNull(scores.getConfigurationSection(key));
            for (String uuid : list.getKeys(false)) {
                UUID u = UUID.fromString(uuid);
                ConfigurationSection r = Objects.requireNonNull(list.getConfigurationSection(uuid));
                long best = r.getLong("best-ms", -1), last = r.getLong("last-ms", -1);
                if (best < 0 || last < 0) throw new IOException("Invalid time for " + uuid);
                entries.put(u, new Result(u, r.getString("name", uuid), best, last));
            }
            all.put(key, Map.copyOf(entries));
        }
        results = Map.copyOf(all);
    }
    public Map<String, Course> courses() { return courses; }
    public synchronized void put(Course c) {
        putTransient(c); queue();
    }
    public synchronized void putTransient(Course c) {
        Map<String, Course> changed = new HashMap<>(courses); changed.put(c.id(), c);
        courses = Map.copyOf(changed);
    }
    public synchronized void flush() { queue(); }
    public synchronized void delete(String id) {
        Map<String, Course> changed = new HashMap<>(courses); changed.remove(id);
        courses = Map.copyOf(changed); queue();
    }
    public synchronized boolean record(Course course, UUID uuid, String name, long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative time");
        Map<UUID, Result> entries = new HashMap<>(results.getOrDefault(course.resultKey(), Map.of()));
        Result previous = entries.get(uuid);
        boolean pb = previous == null || millis < previous.best();
        entries.put(uuid, new Result(uuid, name, previous == null ? millis : Math.min(previous.best(), millis), millis));
        Map<String, Map<UUID, Result>> all = new HashMap<>(results);
        all.put(course.resultKey(), Map.copyOf(entries)); results = Map.copyOf(all);
        topCache.remove(course.resultKey()); queue(); return pb;
    }
    public synchronized Result result(Course c, UUID uuid) {
        return results.getOrDefault(c.resultKey(), Map.of()).get(uuid);
    }
    public synchronized List<Result> ranking(Course c) {
        return topCache.computeIfAbsent(c.resultKey(), key -> results.getOrDefault(key, Map.of()).values().stream()
                .sorted(Comparator.comparingLong(Result::best).thenComparing(r -> r.uuid().toString())).toList());
    }
    public synchronized int rank(Course c, UUID uuid) {
        List<Result> list = ranking(c);
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid().equals(uuid)) return i + 1;
        return 0;
    }
    private void queue() {
        pending.set(new Snapshot(courses, results));
        if (scheduled.compareAndSet(false, true)) writer.execute(this::drain);
    }
    private void drain() {
        try {
            Snapshot s;
            while ((s = pending.getAndSet(null)) != null) {
                try { write(s); } catch (Exception ex) { error.accept(ex); }
            }
        } finally {
            scheduled.set(false);
            if (pending.get() != null && scheduled.compareAndSet(false, true)) drain();
        }
    }
    private void write(Snapshot s) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("schema", 2);
        new TreeMap<>(s.courses()).forEach((id, c) -> {
            String p = "courses." + id + ".";
            y.set(p + "layout", c.layout()); y.set(p + "enabled", c.enabled());
            for (int i = 0; i < c.rings().size(); i++) {
                Ring r = c.rings().get(i); String q = p + "rings." + (i + 1) + ".";
                y.set(q + "world", r.world().toString()); y.set(q + "x", r.center().x());
                y.set(q + "y", r.center().y()); y.set(q + "z", r.center().z());
                y.set(q + "rotation.x", r.rotation().x()); y.set(q + "rotation.y", r.rotation().y());
                y.set(q + "rotation.z", r.rotation().z()); y.set(q + "rotation.w", r.rotation().w());
                y.set(q + "radius", r.radius());
            }
        });
        s.results().forEach((key, entries) -> entries.forEach((uuid, r) -> {
            String p = "results." + key + "." + uuid + ".";
            y.set(p + "name", r.name()); y.set(p + "best-ms", r.best()); y.set(p + "last-ms", r.last());
        }));
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, y.saveToString(), StandardCharsets.UTF_8);
        if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) error.accept(new IOException("State save still running after 10 seconds"));
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); error.accept(ex); }
    }
}
