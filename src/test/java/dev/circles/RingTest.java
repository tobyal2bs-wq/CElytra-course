package dev.circles;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RingTest {
    private final Ring ring = new Ring(RaceTest.WORLD, new Vec(0, 3.5, 0), 0, 3.5);
    @Test void ringIsSevenBySevenAndDense() {
        assertEquals(3.5, ring.point(0, 128).x(), 1e-10);
        assertEquals(7, ring.point(32, 128).y(), 1e-10);
        assertEquals(-3.5, ring.point(64, 128).x(), 1e-10);
        assertEquals(0, ring.point(96, 128).y(), 1e-10);
        for (int i = 0; i < 128; i++) {
            assertEquals(12.25, ring.point(i, 128).sub(ring.center()).lengthSquared(), 1e-9);
            assertTrue(ring.point(i, 128).sub(ring.point((i + 1) % 128, 128)).lengthSquared() < .04);
        }
    }
    @Test void crossingDiskInEitherDirectionCounts() {
        assertEquals(.5, ring.crossing(new Vec(0, .9, -1), new Vec(0, .9, 1)).orElseThrow(), 1e-9);
        assertEquals(.5, ring.crossing(new Vec(0, .9, 1), new Vec(0, .9, -1)).orElseThrow(), 1e-9);
    }
    @Test void nearRingOrOutsideOpeningDoesNotCount() {
        assertTrue(ring.crossing(new Vec(0, 3.5, 1), new Vec(0, 3.5, 2)).isEmpty());
        assertTrue(ring.crossing(new Vec(4, 3.5, -1), new Vec(4, 3.5, 1)).isEmpty());
        assertTrue(ring.crossing(new Vec(0, 7.1, -1), new Vec(0, 7.1, 1)).isEmpty());
        assertTrue(ring.crossing(new Vec(0, 3.5, 1), new Vec(1, 3.5, 1)).isEmpty());
    }
    @Test void crossingIsDetectedEvenWhenNeitherEndpointIsInRing() {
        assertEquals(.5, ring.crossing(new Vec(0, 3.5, -10), new Vec(0, 3.5, 10)).orElseThrow());
    }
    @Test void arrivingOnPlaneCountsOnceAndLeavingItDoesNotRepeat() {
        assertTrue(ring.crossing(new Vec(0, 3.5, -1), new Vec(0, 3.5, 0)).isPresent());
        assertTrue(ring.crossing(new Vec(0, 3.5, 0), new Vec(0, 3.5, 1)).isEmpty());
        assertTrue(ring.crossing(new Vec(0, 3.5, 0), new Vec(1, 3.5, 0)).isEmpty());
    }
    @Test void yawRotatesRingAndCrossingPlaneTogether() {
        Ring rotated = new Ring(RaceTest.WORLD, new Vec(0, 3.5, 0), 90, 3.5);
        assertTrue(rotated.crossing(new Vec(-1, 3.5, 0), new Vec(1, 3.5, 0)).isPresent());
        assertTrue(rotated.crossing(new Vec(1, 3.5, -1), new Vec(1, 3.5, 1)).isEmpty());
        assertEquals(3.5, rotated.point(0, 128).z(), 1e-9);
    }
    @Test void rejectsInvalidGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new Ring(RaceTest.WORLD, new Vec(0, 0, 0), 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Ring(RaceTest.WORLD, new Vec(Double.NaN, 0, 0), 0, 3.5));
        assertThrows(IllegalArgumentException.class, () -> new Ring(RaceTest.WORLD, new Vec(0, 0, 0), 0, 0));
    }
}
