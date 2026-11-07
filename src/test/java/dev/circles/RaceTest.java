package dev.circles;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceTest {
    static final UUID WORLD = UUID.randomUUID();
    static Course course(String id, int count) {
        List<Ring> rings = new ArrayList<>();
        for (int i = 0; i < count; i++) rings.add(new Ring(WORLD, new Vec(0, 67.5, i * 10), 0, 3.5));
        return new Course(id, UUID.randomUUID().toString(), true, rings);
    }
    @Test void skippedCheckpointDoesNotAdvanceOrFinish() {
        Race race = new Race(course("spawn", 3), 1_000_000);
        assertEquals(Race.Outcome.SKIPPED, race.cross(3)); assertEquals(1, race.passed());
        assertEquals(Race.Outcome.CHECKPOINT, race.cross(2));
        assertEquals(Race.Outcome.DONE, race.cross(3)); assertEquals(3, race.passed());
    }
    @Test void completedAndOldCheckpointsCannotAdvanceTwice() {
        Race race = new Race(course("spawn", 3), 0);
        assertEquals(Race.Outcome.OLD, race.cross(1));
        assertEquals(Race.Outcome.CHECKPOINT, race.cross(2));
        assertEquals(Race.Outcome.OLD, race.cross(2)); assertEquals(2, race.passed());
        assertEquals(Race.Outcome.DONE, race.cross(3));
        assertEquals(Race.Outcome.OLD, race.cross(3));
    }
    @Test void playersAndCoursesHaveIndependentProgress() {
        Race a = new Race(course("spawn", 100), 1_000_000_000L);
        Race b = new Race(course("parkour", 3), 2_000_000_000L);
        Race c = new Race(a.course(), 3_000_000_000L);
        for (int i = 2; i <= 10; i++) a.cross(i);
        assertEquals(10, a.passed()); assertEquals(1, b.passed()); assertEquals(1, c.passed());
        assertEquals(81_011, a.elapsedMillis(82_011_000_000L));
        assertEquals(80_011, b.elapsedMillis(82_011_000_000L));
    }
    @Test void supportsMoreThanOneHundredCheckpoints() {
        Race race = new Race(course("long-course", 1500), 0);
        for (int i = 2; i < 1500; i++) assertEquals(Race.Outcome.CHECKPOINT, race.cross(i));
        assertEquals(Race.Outcome.DONE, race.cross(1500));
    }
    @Test void editsDisableCourseAndCreateSeparateResultHistory() {
        Course original = course("spawn", 3);
        Course edited = original.edit(original.rings());
        assertFalse(edited.enabled()); assertNotEquals(original.resultKey(), edited.resultKey());
        assertEquals(edited.resultKey(), edited.enabled(true).resultKey());
        assertThrows(IllegalArgumentException.class, () -> course("spawn", 1).enabled(true));
    }
    @Test void timeFormattingIncludesThreeMillisecondDigitsAndDoesNotWrapMinutes() {
        assertEquals("01:21.011", Times.format(81_011));
        assertEquals("00:00.001", Times.format(1));
        assertEquals("00:59.999", Times.format(59_999));
        assertEquals("01:00.000", Times.format(60_000));
        assertEquals("120:00.000", Times.format(7_200_000));
        assertEquals("--:--.---", Times.format(-1));
    }
    @Test void validatesCourseIdsAndDependencyVersions() {
        assertThrows(IllegalArgumentException.class, () -> course("spawn_bad", 3));
        assertEquals(0, CircleCheckpoints.compareVersion("2.14.0", 2, 14, 0));
        assertEquals(0, CircleCheckpoints.compareVersion("v2.14.0-SNAPSHOT", 2, 14, 0));
        assertTrue(CircleCheckpoints.compareVersion("2.13.9", 2, 14, 0) < 0);
        assertTrue(CircleCheckpoints.compareVersion("2.15.0", 2, 14, 0) > 0);
    }
}
