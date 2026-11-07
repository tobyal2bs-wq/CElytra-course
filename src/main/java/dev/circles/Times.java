package dev.circles;

import java.util.Locale;

public final class Times {
    private Times() {}
    public static String format(long millis) {
        if (millis < 0) return "--:--.---";
        return String.format(Locale.ROOT, "%02d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }
}
