package dev.circles;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RotationEditorTest {
    private static void vec(Vec expected, Vec actual) {
        assertEquals(expected.x(), actual.x(), 1e-6);
        assertEquals(expected.y(), actual.y(), 1e-6);
        assertEquals(expected.z(), actual.z(), 1e-6);
    }
    @Test void anyDegreeRotationKeepsGraphicsAndDetectionOnTheSamePlane() {
        Random random = new Random(73);
        for (int i = 0; i < 500; i++) {
            Rotation rotation = Rotation.degrees(random.nextDouble()*720-360, random.nextDouble()*360-180, random.nextDouble()*360-180);
            Ring r = new Ring(RaceTest.WORLD, new Vec(4, 75, 8), rotation, 3.5);
            Vec normal = r.normal(); assertEquals(1, normal.lengthSquared(), 1e-9);
            for (int j = 0; j < 8; j++) {
                Vec p = r.point(j, 8).sub(r.center());
                assertEquals(0, p.dot(normal), 1e-9); assertEquals(12.25, p.lengthSquared(), 1e-9);
            }
            assertEquals(.5, r.crossing(r.center().sub(normal.mul(2)), r.center().add(normal.mul(2))).orElseThrow(), 1e-8);
            Vec outside = r.center().add(rotation.apply(new Vec(4, 0, 0)));
            assertTrue(r.crossing(outside.sub(normal), outside.add(normal)).isEmpty());
        }
    }
    @Test void horizontalRingDetectsVerticalPassage() {
        Ring r = new Ring(RaceTest.WORLD, new Vec(0, 70, 0), Rotation.degrees(27.25, 90, 12.3), 3.5);
        assertTrue(r.crossing(new Vec(0, 65, 0), new Vec(0, 75, 0)).isPresent());
        assertTrue(r.crossing(new Vec(-1, 71, 0), new Vec(1, 71, 0)).isEmpty());
    }
    @Test void denseBandHasHundredsOfDifferentRadiiInsteadOfConcentricRows() {
        Ring r = new Ring(RaceTest.WORLD, new Vec(0, 0, 0), Rotation.IDENTITY, 3.5);
        Set<Long> radii = new HashSet<>();
        for (int i = 0; i < 640; i++) {
            Vec p = r.orbPoint(i, 640, .7); double radius = Math.hypot(p.x(), p.y());
            assertTrue(radius >= 2.8 && radius <= 3.5); assertTrue(Math.abs(p.z()) <= .09);
            radii.add(Math.round(radius*10000));
            assertEquals(p, r.orbPoint(i, 640, .7), "Stable positions must not jitter on refresh");
        }
        assertTrue(radii.size() > 550, "A continuous cloud must not collapse into a handful of visible lines");
        assertTrue(r.crossing(new Vec(3.2, 0, -1), new Vec(3.2, 0, 1), 2.8).isEmpty());
        assertTrue(r.crossing(new Vec(2.5, 0, -1), new Vec(2.5, 0, 1), 2.8).isPresent());
    }
    @Test void compactBandPacksMoreOrbsIntoASmallerWidthAndDepth() {
        Ring r = new Ring(RaceTest.WORLD, new Vec(0,0,0), Rotation.IDENTITY, 3.5);
        double inner = 3.5-.45;
        for (int i=0;i<960;i++) {
            Vec p = r.orbPoint(i,960,.45);
            assertTrue(Math.hypot(p.x(),p.y()) >= inner);
            assertTrue(Math.hypot(p.x(),p.y()) <= 3.5);
            assertTrue(Math.abs(p.z()) <= .03);
        }
        double oldDensity = 640/(Math.PI*(3.5*3.5-2.8*2.8));
        double density = 960/(Math.PI*(3.5*3.5-inner*inner));
        assertTrue(density > oldDensity*2);
    }
    @Test void itemDisplayCombinesEntityPoseTranslationAndBothQuaternions() {
        Rotation left = Rotation.degrees(0, 32.5, 0), right = Rotation.degrees(0, 0, 19.25);
        Transformation t = new Transformation(new Vector3f(1, 2, 3),
                new Quaternionf((float)left.x(),(float)left.y(),(float)left.z(),(float)left.w()),
                new Vector3f(.22f), new Quaternionf((float)right.x(),(float)right.y(),(float)right.z(),(float)right.w()));
        Ring r = MarkerPose.read(RaceTest.WORLD, new Vec(10, 70, 20), 45.25f, -12.5f, t, 3.5);
        Rotation base = Rotation.degrees(45.25, -12.5, 0);
        vec(new Vec(10, 70, 20).add(base.apply(new Vec(1, 2, 3))), r.center());
        assertTrue(r.rotation().near(base.multiply(left).multiply(right)));
    }
    @Test void initialMarkerTransformPreservesTheExactRingCenterAndPlane() {
        Ring original = new Ring(RaceTest.WORLD, new Vec(5.125, 82.3, -25.7), Rotation.degrees(71.23, -36.8, 22.5), 3.5);
        Ring decoded = MarkerPose.read(original.world(), original.center(), 0, 0, MarkerPose.initial(original), original.radius());
        assertTrue(decoded.near(original)); vec(original.center(), decoded.center());
    }
    @Test void editingChangesLayoutOnceAndNeverOverwritesAnExternalEdit() {
        Course original = RaceTest.course("spawn", 3).enabled(false);
        EditDraft draft = new EditDraft(); Ring r = original.rings().getFirst();
        assertSame(original, draft.move(original, 0, r, r));
        Ring moved = new Ring(r.world(), r.center().add(new Vec(1, 0, 0)), Rotation.degrees(27, 40, 9), r.radius());
        Course a = draft.move(original, 0, r, moved); assertNotNull(a);
        assertNotEquals(original.layout(), a.layout()); assertFalse(a.enabled());
        Ring movedAgain = new Ring(r.world(), moved.center().add(new Vec(0, 2, 0)), moved.rotation(), r.radius());
        Course b = draft.move(a, 0, moved, movedAgain); assertNotNull(b); assertEquals(a.layout(), b.layout());
        assertNull(draft.move(b, 0, r, moved), "A stale handle must not overwrite a newer checkpoint");
        assertNull(draft.move(b.enabled(true), 0, movedAgain, r), "Enabled courses cannot be moved by a handle");
    }
    @Test void invalidOrEquivalentRotationsAreHandledSafely() {
        assertThrows(IllegalArgumentException.class, () -> Rotation.degrees(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(0,0,0,0));
        assertTrue(Rotation.degrees(12.5, 90, 3.5).near(Rotation.degrees(372.5, 90, 363.5)));
        assertTrue(new Rotation(0,0,0,-1).near(Rotation.IDENTITY));
    }
}
