package dev.circles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    @TempDir Path dir;
    private Store open(List<Throwable> errors) throws Exception { return new Store(dir.resolve("state.yml"), errors::add); }
    @Test void persistsCourseBestLastAndPerCourseLeaderboards() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Course spawn = RaceTest.course("spawn", 3), parkour = RaceTest.course("parkour", 5);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (Store s = open(errors)) {
            s.put(spawn); s.put(parkour);
            assertTrue(s.record(spawn, a, "Alex", 81_011));
            assertFalse(s.record(spawn, a, "Alex", 90_000));
            s.record(spawn, b, "Steve", 70_000); s.record(parkour, a, "Alex", 120_000);
            assertEquals(81_011, s.result(spawn, a).best()); assertEquals(90_000, s.result(spawn, a).last());
            assertEquals(b, s.ranking(spawn).getFirst().uuid()); assertEquals(2, s.rank(spawn, a));
            assertEquals(1, s.rank(parkour, a)); assertEquals(0, s.rank(parkour, b));
        }
        try (Store s = open(errors)) {
            assertEquals(spawn, s.courses().get("spawn")); assertEquals(2, s.ranking(spawn).size());
            assertEquals(90_000, s.result(spawn, a).last()); assertEquals(120_000, s.result(parkour, a).best());
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    @Test void editingLayoutArchivesOldScoresWithoutMixingThem() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        UUID player = UUID.randomUUID(); Course old = RaceTest.course("spawn", 3), edited = old.edit(old.rings());
        try (Store s = open(errors)) {
            s.put(old); s.record(old, player, "Alex", 1000); s.put(edited);
            assertNull(s.result(edited, player)); assertEquals(1000, s.result(old, player).best());
        }
        try (Store s = open(errors)) {
            assertNull(s.result(edited, player)); assertEquals(1000, s.result(old, player).best());
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    @Test void concurrentFinishesPreserveAllRecordsAndRankingIsStable() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>(); Course c = RaceTest.course("spawn", 3);
        try (Store s = open(errors)) {
            s.put(c);
            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<?>> jobs = new ArrayList<>();
                for (int i = 1; i <= 100; i++) {
                    int n = i;
                    jobs.add(pool.submit(() -> s.record(c, new UUID(0, n), "Player" + n, 1000 + n)));
                }
                for (Future<?> job : jobs) job.get();
            }
            assertEquals(100, s.ranking(c).size()); assertEquals(1001, s.ranking(c).getFirst().best());
            assertEquals(100, s.rank(c, new UUID(0, 100)));
        }
        try (Store s = open(errors)) { assertEquals(100, s.ranking(c).size()); }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    @Test void malformedFileIsRejectedAndPreserved() throws Exception {
        Path file = dir.resolve("state.yml"); String broken = "courses: [invalid\n";
        Files.writeString(file, broken);
        assertThrows(Exception.class, () -> new Store(file, ex -> {}));
        assertEquals(broken, Files.readString(file));
    }
    @Test void topTenAndViewerPlaceholdersUseTheCorrectPlayerAndCourse() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>(); Course c = RaceTest.course("spawn", 3);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (Store s = open(errors)) {
            s.put(c); s.record(c, a, "Alex", 81_011); s.record(c, b, "Steve", 70_000);
            assertEquals("01:21.011", CirclesExpansion.resolve(s, null, a, "spawn_best"));
            assertEquals("01:10.000", CirclesExpansion.resolve(s, null, b, "spawn_best"));
            assertEquals("Steve", CirclesExpansion.resolve(s, null, a, "spawn_top_1_name"));
            assertEquals("01:10.000", CirclesExpansion.resolve(s, null, a, "spawn_top_1_time"));
            assertEquals("-", CirclesExpansion.resolve(s, null, a, "spawn_top_10_name"));
            assertEquals("--:--.---", CirclesExpansion.resolve(s, null, null, "spawn_best"));
            assertEquals("--:--.---", CirclesExpansion.resolve(s, null, a, "other_best"));
            assertEquals("-", CirclesExpansion.resolve(s, null, a, "spawn_top_11_name"));
            assertNull(CirclesExpansion.resolve(s, null, a, "spawn_top_101_name"));
            assertEquals("3", CirclesExpansion.resolve(s, null, a, "spawn_total"));
            assertEquals("81011", CirclesExpansion.resolve(s, null, a, "spawn_best_ms"));
            assertEquals("81011", CirclesExpansion.resolve(s, null, a, "spawn_last_ms"));
            assertEquals("-1", CirclesExpansion.resolve(s, null, a, "spawn_time_ms"));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    @Test void schemaOneUpgradePreservesTimesAndYawAndWritesQuaternionOnNextSave() throws Exception {
        UUID layout = UUID.randomUUID(), player = UUID.randomUUID();
        Path file = dir.resolve("state.yml");
        String legacy = "schema: 1\ncourses:\n  spawn:\n    layout: '"+layout+"'\n    enabled: false\n    rings:\n      '1':\n        world: '"+RaceTest.WORLD+"'\n        x: 2.5\n        y: 67.5\n        z: 6.0\n        yaw: 27.25\n        radius: 3.5\nresults:\n  spawn@"+layout+":\n    "+player+":\n      name: Alex\n      best-ms: 81011\n      last-ms: 90000\n";
        Files.writeString(file, legacy);
        List<Throwable> errors = new ArrayList<>();
        try (Store s = open(errors)) {
            Course c = s.courses().get("spawn");
            assertEquals(layout.toString(), c.layout());
            assertTrue(c.rings().getFirst().rotation().near(Rotation.degrees(27.25, 0, 0)));
            assertEquals(81011, s.result(c, player).best());
            assertEquals(90000, s.result(c, player).last());
            assertEquals(legacy, Files.readString(file), "Loading alone must not rewrite old data");
            s.flush();
        }
        assertTrue(Files.readString(file).contains("schema: 2"));
        assertEquals(legacy, Files.readString(file.resolveSibling("state.yml.bak")));
        try (Store s = open(errors)) { assertEquals(81011, s.result(s.courses().get("spawn"), player).best()); }
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
