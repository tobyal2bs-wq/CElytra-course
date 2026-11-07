package dev.circles;

import java.util.UUID;
import java.util.OptionalDouble;

/** Immutable geometry, with unrestricted three-axis orientation. */
public record Ring(UUID world, Vec center, Rotation rotation, double radius) {
    public Ring {
        if (world == null || center == null || !Double.isFinite(center.x()) || !Double.isFinite(center.y())
                || !Double.isFinite(center.z()) || rotation == null || !Double.isFinite(radius)
                || radius < 1 || radius > 16) throw new IllegalArgumentException("Invalid ring geometry");
    }
    public Ring(UUID world, Vec center, float yaw, double radius) {
        this(world, center, Rotation.degrees(yaw, 0, 0), radius);
    }
    public float yaw() {
        Vec n = normal(); return (float) Math.toDegrees(Math.atan2(-n.x(), n.z()));
    }
    public float pitch() { return (float) Math.toDegrees(-Math.asin(Math.clamp(normal().y(), -1, 1))); }
    public Vec normal() {
        return rotation.apply(new Vec(0, 0, 1));
    }
    public Vec point(int index, int count) {
        double a = 2 * Math.PI * index / count;
        return center.add(rotation.apply(new Vec(Math.cos(a) * radius, Math.sin(a) * radius, 0)));
    }
    /** One continuous annular cloud: no discrete radial rows or repeated concentric circles. */
    public Vec orbPoint(int index, int count, double bandWidth) {
        double width = effectiveWidth(bandWidth), inner = radius - width;
        double a = 2 * Math.PI * fraction((index + .5) * .6180339887498949);
        double radial = Math.sqrt(inner*inner + random(index, 19) * (radius*radius-inner*inner));
        double depth = (random(index, 71) - .5) * Math.min(.06, width * .2);
        return center.add(rotation.apply(new Vec(Math.cos(a)*radial, Math.sin(a)*radial, depth)));
    }
    public double effectiveWidth(double width) { return Math.clamp(width, .01, radius*.45); }
    private static double fraction(double n) { return n - Math.floor(n); }
    private static double random(int index, int salt) {
        int n = index * 0x9e3779b9 + salt;
        n ^= n >>> 16; n *= 0x7feb352d; n ^= n >>> 15; n *= 0x846ca68b; n ^= n >>> 16;
        return Integer.toUnsignedLong(n) / 4294967296.0;
    }
    public boolean near(Ring other) {
        return world.equals(other.world) && center.sub(other.center).lengthSquared() < 1e-6
                && rotation.near(other.rotation) && Math.abs(radius-other.radius) < 1e-6;
    }
    /** Exact segment/plane intersection: proximity to the rim alone never counts. */
    public OptionalDouble crossing(Vec from, Vec to) {
        return crossing(from, to, radius);
    }
    public OptionalDouble crossing(Vec from, Vec to, double openingRadius) {
        Vec n = normal();
        double a = from.sub(center).dot(n), b = to.sub(center).dot(n);
        if (Math.abs(a - b) < 1e-9 || (a > 0 && b > 0) || (a < 0 && b < 0)) return OptionalDouble.empty();
        // Starting on the plane is not a new crossing; arriving at it is.
        if (Math.abs(a) < 1e-9) return OptionalDouble.empty();
        double t = a / (a - b);
        Vec offset = from.add(to.sub(from).mul(t)).sub(center);
        return offset.lengthSquared() <= openingRadius * openingRadius ? OptionalDouble.of(t) : OptionalDouble.empty();
    }
}
