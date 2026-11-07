package dev.circles;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.replacer.CharsReplacer;
import me.clip.placeholderapi.replacer.Replacer;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ElytraExpansionTest {
    @TempDir Path dir;
    private OfflinePlayer player(UUID uuid) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (p,m,args) -> {
                    if (m.getName().equals("getUniqueId")) return uuid;
                    throw new AssertionError("Unexpected player access: " + m.getName());
                });
    }
    @Test void actualPlaceholderApiParserAcceptsBareElytraAndRanksThroughOneHundred() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Course course = RaceTest.course("elytra", 3); UUID viewer = new UUID(0, 11);
        try (Store store = new Store(dir.resolve("state.yml"), errors::add)) {
            store.put(course);
            // Insert slow-to-fast, with several slower later runs: rank must use each UUID's lowest time.
            for (int i = 100; i >= 1; i--) {
                store.record(course, new UUID(0,i), "Player" + i, 60_000+i*100);
                store.record(course, new UUID(0,i), "Player" + i, 80_000+i*100);
            }
            PlaceholderExpansion expansion = new PlaceholderExpansion() {
                public String getIdentifier() { return "elytra"; }
                public String getAuthor() { return "test"; }
                public String getVersion() { return "test"; }
                public String onRequest(OfflinePlayer p, String params) {
                    return ElytraExpansion.resolve(store, null, p==null?null:p.getUniqueId(), "elytra", params);
                }
            };
            CharsReplacer parser = new CharsReplacer(Replacer.Closure.PERCENT);
            String parsed = parser.apply("Your best: %elytra% | 1. %elytra_1% | 10. %elytra_10% | 100. %elytra_100%",
                    player(viewer), id -> id.equals("elytra") ? expansion : null);
            assertEquals("Your best: 01:01.100 | 1. Player1 - 01:00.100 | 10. Player10 - 01:01.000 | 100. Player100 - 01:10.000", parsed);
            assertEquals("01:00.100", parser.apply("%elytra%", player(new UUID(0,1)), id -> expansion));
            assertEquals("%elytra_101%", parser.apply("%elytra_101%", player(viewer), id -> expansion));
            assertEquals("Player10", ElytraExpansion.resolve(store, null, viewer, "elytra", "10_name"));
            assertEquals("01:01.000", ElytraExpansion.resolve(store, null, viewer, "elytra", "10_time"));
            assertEquals("Player100", CirclesExpansion.resolve(store, null, viewer, "elytra_top_100_name"));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    @Test void selectedCoursesAndViewerResultsStaySeparate() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>(); UUID viewer = UUID.randomUUID();
        try (Store store = new Store(dir.resolve("state.yml"), errors::add)) {
            Course spawn = RaceTest.course("spawn", 3), parkour = RaceTest.course("parkour", 5);
            store.put(spawn); store.put(parkour);
            store.record(spawn, viewer, "Alex", 81_011); store.record(parkour, viewer, "Alex", 122_456);
            assertEquals("01:21.011", ElytraExpansion.resolve(store, null, viewer, "auto", ""));
            assertEquals("02:02.456", ElytraExpansion.resolve(store, null, viewer, "parkour", ""));
            assertEquals("Alex - 02:02.456", ElytraExpansion.resolve(store, null, viewer, "parkour", "1"));
            assertEquals("--:--.---", ElytraExpansion.resolve(store, null, UUID.randomUUID(), "parkour", ""));
            assertEquals("--:--.---", ElytraExpansion.resolve(store, null, null, "parkour", ""));
            assertEquals("-", ElytraExpansion.resolve(store, null, viewer, "parkour", "100"));
            assertEquals("--:--.---", ElytraExpansion.resolve(store, null, viewer, "parkour", "100_time"));
            assertEquals("--:--.---", ElytraExpansion.resolve(store, null, viewer, "missing", ""));
            assertNull(ElytraExpansion.resolve(store, null, viewer, "parkour", "1-100"));
            assertNull(ElytraExpansion.resolve(store, null, viewer, "parkour", "0"));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    @Test void automaticCourseSelectionIsPredictableAndDoesNotFollowTheViewersActiveTrack() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>(); UUID viewer = UUID.randomUUID();
        try (Store store = new Store(dir.resolve("state.yml"), errors::add)) {
            Course parkour = RaceTest.course("parkour", 3); store.put(parkour);
            assertEquals("parkour", ElytraExpansion.resolve(store, null, viewer, "auto", "course"));
            store.put(RaceTest.course("other", 3)); assertNull(ElytraExpansion.target(store, "auto"));
            store.put(RaceTest.course("spawn", 3)); assertEquals("spawn", ElytraExpansion.target(store, "auto").id());
            store.put(RaceTest.course("elytra", 3)); assertEquals("elytra", ElytraExpansion.target(store, "auto").id());
            var live = new CircleCheckpoints.RunView(parkour.id(), parkour.layout(), 2, 3, System.nanoTime()-81_011_000_000L);
            assertEquals("--:--.---", ElytraExpansion.resolve(store, live, viewer, "auto", "live"));
            assertTrue(ElytraExpansion.resolve(store, live, viewer, "parkour", "live").startsWith("01:21."));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
