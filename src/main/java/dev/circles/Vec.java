package dev.circles;

public record Vec(double x, double y, double z) {
    public Vec add(Vec o) { return new Vec(x + o.x, y + o.y, z + o.z); }
    public Vec sub(Vec o) { return new Vec(x - o.x, y - o.y, z - o.z); }
    public Vec mul(double m) { return new Vec(x * m, y * m, z * m); }
    public double dot(Vec o) { return x * o.x + y * o.y + z * o.z; }
    public double lengthSquared() { return dot(this); }
    public Vec cross(Vec o) { return new Vec(y*o.z-z*o.y, z*o.x-x*o.z, x*o.y-y*o.x); }
}
