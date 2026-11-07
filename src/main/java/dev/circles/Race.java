package dev.circles;

/** Mutable only on the owning player's region thread. */
public final class Race {
    public enum Outcome { OLD, SKIPPED, CHECKPOINT, DONE }
    private final Course course;
    private final long startNanos;
    private int passed = 1;
    public Race(Course course, long startNanos) {
        if (course.rings().size() < 2) throw new IllegalArgumentException("Course requires start and finish");
        this.course = course; this.startNanos = startNanos;
    }
    public Outcome cross(int number) {
        if (number <= passed) return Outcome.OLD;
        if (number != passed + 1) return Outcome.SKIPPED;
        passed = number;
        return passed == course.rings().size() ? Outcome.DONE : Outcome.CHECKPOINT;
    }
    public Course course() { return course; }
    public int passed() { return passed; }
    public long startNanos() { return startNanos; }
    public long elapsedMillis(long now) { return Math.max(0, (now - startNanos) / 1_000_000); }
}
