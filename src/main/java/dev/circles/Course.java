package dev.circles;

import java.util.List;
import java.util.UUID;

public record Course(String id, String layout, boolean enabled, List<Ring> rings) {
    public Course {
        if (id == null || !id.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("Course IDs use 1-32 lowercase letters, digits or hyphens");
        UUID.fromString(layout);
        rings = List.copyOf(rings);
    }
    public String resultKey() { return id + "@" + layout; }
    public Course edit(List<Ring> updated) { return new Course(id, UUID.randomUUID().toString(), false, updated); }
    public Course enabled(boolean value) {
        if (value && rings.size() < 2) throw new IllegalArgumentException("Set at least two rings: start and finish");
        if (value && rings.stream().anyMatch(r -> !r.world().equals(rings.getFirst().world())))
            throw new IllegalArgumentException("A course must be in one world; create a separate course for another world");
        return new Course(id, layout, value, rings);
    }
}
