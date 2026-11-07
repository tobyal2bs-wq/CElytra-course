package dev.circles;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.UUID;

/** Decodes an ItemDisplay's entity pose plus both display rotations and translation. */
final class MarkerPose {
    static Ring read(UUID world, Vec location, float yaw, float pitch, Transformation transform, double radius) {
        Rotation base = Rotation.degrees(yaw, pitch, 0);
        Vector3f translation = transform.getTranslation();
        Vec center = location.add(base.apply(new Vec(translation.x, translation.y, translation.z)));
        Rotation rotation = base.multiply(of(transform.getLeftRotation())).multiply(of(transform.getRightRotation()));
        return new Ring(world, center, rotation, radius);
    }
    static Transformation initial(Ring ring) {
        Rotation r = ring.rotation();
        return new Transformation(new Vector3f(), new Quaternionf((float)r.x(), (float)r.y(), (float)r.z(), (float)r.w()),
                new Vector3f(.22f), new Quaternionf());
    }
    private static Rotation of(Quaternionf q) { return new Rotation(q.x, q.y, q.z, q.w); }
}
