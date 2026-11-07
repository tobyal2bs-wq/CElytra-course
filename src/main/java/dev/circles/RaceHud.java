package dev.circles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class RaceHud {
    private RaceHud() {}
    static Component running(int checkpoint, int total, long millis, String warning) {
        String label = warning == null ? "Checkpoint " + checkpoint + "/" + total : warning;
        return Component.text(label, warning == null ? NamedTextColor.GREEN : NamedTextColor.RED)
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text(Times.format(millis), NamedTextColor.GOLD));
    }
}
