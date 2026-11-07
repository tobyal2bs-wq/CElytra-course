package dev.circles;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import java.util.*;
import java.util.regex.*;

/** Short leaderboard placeholders, all bound to one explicit course instead of mixing tracks. */
public final class ElytraExpansion extends PlaceholderExpansion {
    private static final Pattern RANK = Pattern.compile("^(100|[1-9][0-9]?)(?:_(name|time))?$");
    private final CircleCheckpoints plugin;
    ElytraExpansion(CircleCheckpoints plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "elytra"; }
    @Override public String getAuthor() { return "Lukas"; }
    @Override public String getVersion() { return "1.2.1"; }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String params) {
        UUID uuid = player == null ? null : player.getUniqueId();
        return resolve(plugin.store(), uuid == null ? null : plugin.view(uuid), uuid,
                plugin.leaderboardCourse(), params.toLowerCase(Locale.ROOT));
    }
    static Course target(Store store, String configured) {
        Map<String, Course> courses = store.courses();
        if (!"auto".equals(configured)) return courses.get(configured);
        if (courses.containsKey("elytra")) return courses.get("elytra");
        if (courses.containsKey("spawn")) return courses.get("spawn");
        return courses.size() == 1 ? courses.values().iterator().next() : null;
    }
    static String resolve(Store store, CircleCheckpoints.RunView run, UUID uuid, String configured, String params) {
        Course course = target(store, configured);
        if (params.equals("course")) return course == null ? "-" : course.id();
        Matcher rank = RANK.matcher(params);
        if (rank.matches()) {
            int index = Integer.parseInt(rank.group(1)) - 1;
            List<Store.Result> list = course == null ? List.of() : store.ranking(course);
            if (index >= list.size()) return "time".equals(rank.group(2)) ? Times.format(-1) : "-";
            Store.Result r = list.get(index);
            if ("name".equals(rank.group(2))) return r.name();
            if ("time".equals(rank.group(2))) return Times.format(r.best());
            return r.name() + " - " + Times.format(r.best());
        }
        if (params.isEmpty() || params.equals("best")) {
            Store.Result r = course == null || uuid == null ? null : store.result(course, uuid);
            return Times.format(r == null ? -1 : r.best());
        }
        if (params.equals("last") || params.equals("live")) {
            if (course == null) return Times.format(-1);
            return CirclesExpansion.resolve(store, run, uuid, course.id() + (params.equals("live") ? "_time" : "_last"));
        }
        return null;
    }
}
