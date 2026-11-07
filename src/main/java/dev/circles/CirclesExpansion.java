package dev.circles;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.*;
import java.util.regex.*;

/** Only immutable run snapshots and the synchronized result store are read from PAPI threads. */
public final class CirclesExpansion extends PlaceholderExpansion {
    private static final Pattern TOP = Pattern.compile("^([a-z0-9-]+)_top_(100|[1-9][0-9]?)_(name|time)$");
    private static final Pattern PERSONAL = Pattern.compile("^([a-z0-9-]+)_(best|last|rank|time|best_ms|last_ms|time_ms|checkpoint|total)$");
    private final CircleCheckpoints plugin;
    CirclesExpansion(CircleCheckpoints plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "circles"; }
    @Override public String getAuthor() { return "Lukas"; }
    @Override public String getVersion() { return "1.2.1"; }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String params) {
        UUID uuid = player == null ? null : player.getUniqueId();
        CircleCheckpoints.RunView run = uuid == null ? null : plugin.view(uuid);
        return resolve(plugin.store(), run, uuid, params.toLowerCase(Locale.ROOT));
    }
    static String resolve(Store store, CircleCheckpoints.RunView run, UUID uuid, String params) {
        switch (params) {
            case "course": return run == null ? "-" : run.course();
            case "time": return run == null ? Times.format(-1) : Times.format(run.elapsed());
            case "time_ms": return run == null ? "-1" : "" + run.elapsed();
            case "checkpoint": return run == null ? "0" : "" + run.checkpoint();
            case "total": return run == null ? "0" : "" + run.total();
        }
        Matcher top = TOP.matcher(params);
        if (top.matches()) {
            Course c = store.courses().get(top.group(1));
            int index = Integer.parseInt(top.group(2)) - 1;
            List<Store.Result> ranks = c == null ? List.of() : store.ranking(c);
            if (index >= ranks.size()) return top.group(3).equals("name") ? "-" : Times.format(-1);
            Store.Result r = ranks.get(index);
            return top.group(3).equals("name") ? r.name() : Times.format(r.best());
        }
        Matcher personal = PERSONAL.matcher(params);
        if (!personal.matches()) return null;
        Course c = store.courses().get(personal.group(1));
        String kind = personal.group(2);
        if (kind.equals("total")) return c == null ? "0" : "" + c.rings().size();
        boolean active = run != null && c != null && run.course().equals(c.id()) && run.layout().equals(c.layout());
        if (kind.equals("checkpoint")) return active ? "" + run.checkpoint() : "0";
        if (kind.equals("time")) return active ? Times.format(run.elapsed()) : Times.format(-1);
        if (kind.equals("time_ms")) return active ? "" + run.elapsed() : "-1";
        Store.Result r = c == null || uuid == null ? null : store.result(c, uuid);
        return switch (kind) {
            case "best" -> Times.format(r == null ? -1 : r.best());
            case "last" -> Times.format(r == null ? -1 : r.last());
            case "best_ms" -> "" + (r == null ? -1 : r.best());
            case "last_ms" -> "" + (r == null ? -1 : r.last());
            case "rank" -> r == null ? "-" : "" + store.rank(c, uuid);
            default -> null;
        };
    }
}
