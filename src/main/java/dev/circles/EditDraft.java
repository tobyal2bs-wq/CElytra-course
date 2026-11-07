package dev.circles;

import java.util.ArrayList;

/** Called under the Store lock. Only the first real movement changes the leaderboard layout ID. */
final class EditDraft {
    private boolean changed;
    Course move(Course current, int index, Ring expected, Ring pose) {
        if (current.enabled() || index < 0 || index >= current.rings().size()
                || !current.rings().get(index).near(expected)) return null;
        if (pose.near(expected)) return current;
        var rings = new ArrayList<>(current.rings()); rings.set(index, pose);
        Course result = changed ? new Course(current.id(), current.layout(), false, rings) : current.edit(rings);
        changed = true;
        return result;
    }
}
