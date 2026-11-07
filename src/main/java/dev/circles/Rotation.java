package dev.circles;

import org.joml.Quaterniond;

/** Immutable unit quaternion; yaw uses Minecraft's clockwise-positive convention. */
public record Rotation(double x, double y, double z, double w) {
    public static final Rotation IDENTITY = new Rotation(0, 0, 0, 1);
    public Rotation {
        double length = Math.sqrt(x*x + y*y + z*z + w*w);
        if (!Double.isFinite(length) || length < 1e-10) throw new IllegalArgumentException("Invalid rotation");
        double sign = w < 0 ? -1 : 1;
        x = x / length * sign; y = y / length * sign; z = z / length * sign; w = w / length * sign;
    }
    public static Rotation degrees(double yaw, double pitch, double roll) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(roll))
            throw new IllegalArgumentException("Rotation angles must be finite numbers");
        Quaterniond q = new Quaterniond().rotationY(Math.toRadians(-yaw % 360))
                .rotateX(Math.toRadians(pitch % 360)).rotateZ(Math.toRadians(roll % 360));
        return new Rotation(q.x, q.y, q.z, q.w);
    }
    public Rotation multiply(Rotation b) {
        return new Rotation(w*b.x+x*b.w+y*b.z-z*b.y, w*b.y-x*b.z+y*b.w+z*b.x,
                w*b.z+x*b.y-y*b.x+z*b.w, w*b.w-x*b.x-y*b.y-z*b.z);
    }
    public Vec apply(Vec v) {
        Vec q = new Vec(x, y, z);
        Vec t = q.cross(v).mul(2);
        return v.add(t.mul(w)).add(q.cross(t));
    }
    public boolean near(Rotation other) {
        return Math.abs(x*other.x+y*other.y+z*other.z+w*other.w) > 1 - 1e-8;
    }
}
