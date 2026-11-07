package dev.circles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RaceHudTest {
    private String plain(Component c) {
        StringBuilder s = new StringBuilder(c instanceof TextComponent t ? t.content() : "");
        c.children().forEach(child -> s.append(plain(child))); return s.toString();
    }
    @Test void actionbarShowsCheckpointAndMillisecondTimerTogether() {
        assertEquals("Checkpoint 10/100 | 01:21.011", plain(RaceHud.running(10,100,81_011,null)));
    }
    @Test void skipWarningKeepsTheTimerVisible() {
        assertEquals("Checkpoint skipped! Pass 2 first. | 02:03.456",
                plain(RaceHud.running(1,3,123_456,"Checkpoint skipped! Pass 2 first.")));
    }
}
