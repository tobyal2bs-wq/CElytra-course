package dev.circles;

import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class CirclesCommand implements CommandExecutor, TabCompleter {
    private final CircleCheckpoints plugin;
    private static final List<String> PLAY = List.of("help", "list", "info", "top", "best", "leave");
    private static final List<String> ADMIN = List.of("create", "set", "setcenter", "rotate", "edit", "leaderboard", "enable", "disable", "removelast", "delete", "tp");
    CirclesCommand(CircleCheckpoints plugin) { this.plugin = plugin; }
    private void say(CommandSender s, String text) { s.sendMessage(plugin.text(text.replace("&lt;", "\\<").replace("&gt;", ">"))); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("circles.use")) { say(sender, "<red>No permission."); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            if (sub.equals("help")) {
                say(sender, "<gold>/circles list | info &lt;course&gt; | top &lt;course&gt; | best &lt;course&gt; | leave");
                say(sender, "<gray>Walk through ring 1 to start. Cross the rest in order; the last ring finishes.");
                if (sender.hasPermission("circles.admin")) {
                    say(sender, "<yellow>/circles create &lt;course&gt;");
                    say(sender, "<yellow>/circles set &lt;course&gt; &lt;number&gt; [radius]");
                    say(sender, "<yellow>/circles setcenter &lt;course&gt; &lt;number&gt; [radius]");
                    say(sender, "<yellow>/circles rotate &lt;course&gt; &lt;number&gt; &lt;yaw&gt; &lt;pitch&gt; &lt;roll&gt;");
                    say(sender, "<yellow>/circles edit &lt;course&gt; [number]</yellow> <gray>- spawn Axiom center handles; enable to finish.");
                    say(sender, "<gray>Stand at the bottom of the ring and look toward the course. Default: 7 x 7.");
                    say(sender, "<yellow>/circles enable|disable|removelast|delete &lt;course&gt;");
                    say(sender, "<yellow>/circles tp &lt;course&gt; &lt;number&gt;");
                    say(sender, "<yellow>/circles leaderboard &lt;course&gt;</yellow> <gray>- choose the course used by %elytra% and %elytra_1% through %elytra_100%.");
                }
                return true;
            }
            if (sub.equals("list")) {
                if (plugin.store().courses().isEmpty()) say(sender, "<gray>No courses yet.");
                plugin.store().courses().values().stream().sorted(Comparator.comparing(Course::id)).forEach(c ->
                        sender.sendMessage(plugin.text("<yellow><course></yellow> <gray>- <count> rings (<status>)",
                                "course", c.id(), "count", "" + c.rings().size(), "status", c.enabled() ? "enabled" : "disabled")));
                return true;
            }
            if (sub.equals("leave")) {
                if (!(sender instanceof Player p)) throw new IllegalArgumentException("Use this command in-game.");
                plugin.leave(p); return true;
            }
            if (List.of("info", "best", "top").contains(sub)) {
                require(args, 2, "/circles " + sub + " <course>");
                Course c = course(args[1]);
                switch (sub) {
                    case "info" -> {
                        sender.sendMessage(plugin.text("<gold><course></gold> <gray>- <count> rings; <status>. Ring 1 starts, ring <count> finishes.",
                                "course", c.id(), "count", "" + c.rings().size(), "status", c.enabled() ? "enabled" : "disabled"));
                        if (sender.hasPermission("circles.admin")) for (int i = 0; i < c.rings().size(); i++) {
                            Ring r = c.rings().get(i);
                            sender.sendMessage(plugin.text("<gray>Ring <n>: center <xyz>, diameter <diameter>, yaw <yaw>, pitch <pitch>, world <world>",
                                    "n", "" + (i + 1), "xyz", String.format(Locale.ROOT, "%.2f %.2f %.2f", r.center().x(), r.center().y(), r.center().z()),
                                    "diameter", "" + (r.radius() * 2), "yaw", "" + r.yaw(), "pitch", "" + r.pitch(), "world", r.world().toString()));
                        }
                    }
                    case "best" -> {
                        if (!(sender instanceof Player p)) throw new IllegalArgumentException("Use /circles top <course> from console.");
                        Store.Result r = plugin.store().result(c, p.getUniqueId());
                        sender.sendMessage(plugin.text("<yellow><course></yellow> <gray>Best: <best> | Last: <last> | Rank: <rank>",
                                "course", c.id(), "best", Times.format(r == null ? -1 : r.best()),
                                "last", Times.format(r == null ? -1 : r.last()), "rank", r == null ? "-" : "" + plugin.store().rank(c, p.getUniqueId())));
                    }
                    case "top" -> {
                        sender.sendMessage(plugin.text("<gold>Top 10 - <course>", "course", c.id()));
                        List<Store.Result> ranks = plugin.store().ranking(c);
                        if (ranks.isEmpty()) say(sender, "<gray>No completed runs yet.");
                        for (int i = 0; i < Math.min(10, ranks.size()); i++) {
                            Store.Result r = ranks.get(i);
                            sender.sendMessage(plugin.text("<yellow><rank>.</yellow> <white><name></white> <gold><time>",
                                    "rank", "" + (i + 1), "name", r.name(), "time", Times.format(r.best())));
                        }
                    }
                }
                return true;
            }
            if (!ADMIN.contains(sub)) throw new IllegalArgumentException("Unknown subcommand. Use /circles help.");
            if (!sender.hasPermission("circles.admin")) { say(sender, "<red>No permission: circles.admin"); return true; }
            require(args, 2, "/circles " + sub + " <course>");
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("Course IDs use 1-32 lowercase letters, digits or hyphens.");
            synchronized (plugin.store()) {
                if (sub.equals("create")) {
                    if (plugin.store().courses().containsKey(id)) throw new IllegalArgumentException("That course already exists.");
                    plugin.store().put(new Course(id, UUID.randomUUID().toString(), false, List.of()));
                    sender.sendMessage(plugin.text("<green>Created <course>.</green> <gray>Set ring 1, then 2, 3... and enable it.", "course", id));
                    return true;
                }
                Course c = course(id);
                switch (sub) {
                    case "leaderboard" -> {
                        plugin.leaderboardCourse(id);
                        sender.sendMessage(plugin.text("<green>Elytra placeholders now use <course>.</green> <gray>%elytra% shows the viewer's best time; %elytra_1% through %elytra_100% show ranked names and times.", "course", id));
                    }
                    case "edit" -> {
                        if (!(sender instanceof Player p)) throw new IllegalArgumentException("Use the editor in-game.");
                        Integer number = args.length > 2 ? positive(args[2]) : null;
                        plugin.editor().start(id, number, p);
                        say(sender, "<green>Course paused. Creating emerald display handles at the ring centers.</green> <gray>Use Axiom's display gizmos to move and rotate; /circles enable " + id + " saves the last positions and removes handles.");
                    }
                    case "set", "setcenter" -> {
                        require(args, 3, "/circles " + sub + " <course> <number> [radius]");
                        if (!(sender instanceof Player p)) throw new IllegalArgumentException("Place rings in-game.");
                        int number = positive(args[2]);
                        if (number > c.rings().size() + 1) throw new IllegalArgumentException("No gaps: next ring is " + (c.rings().size() + 1) + ". You can also replace an existing ring.");
                        double radius = args.length > 3 ? Double.parseDouble(args[3]) : plugin.defaultRadius();
                        Location at = p.getLocation();
                        boolean center = sub.equals("setcenter");
                        Ring ring = new Ring(at.getWorld().getUID(), new Vec(at.getX(), at.getY() + (center ? 0 : radius), at.getZ()),
                                Rotation.degrees(at.getYaw(), center ? at.getPitch() : 0, 0), radius);
                        List<Ring> rings = new ArrayList<>(c.rings());
                        if (number == rings.size() + 1) rings.add(ring); else rings.set(number - 1, ring);
                        plugin.editor().stop(id); plugin.store().put(c.edit(rings));
                        sender.sendMessage(plugin.text("<green>Placed <course> ring <number> (<diameter> x <diameter>).</green> <gray>Course disabled for editing; enable when ready. Leaderboard starts fresh for this layout.",
                                "course", id, "number", "" + number, "diameter", "" + radius * 2));
                    }
                    case "rotate" -> {
                        require(args, 6, "/circles rotate <course> <number> <yaw> <pitch> <roll>");
                        int number = positive(args[2]);
                        if (number > c.rings().size()) throw new IllegalArgumentException("That ring does not exist.");
                        Rotation rotation = Rotation.degrees(Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
                        Ring old = c.rings().get(number-1);
                        var rings = new ArrayList<>(c.rings()); rings.set(number-1, new Ring(old.world(), old.center(), rotation, old.radius()));
                        plugin.editor().stop(id); plugin.store().put(c.edit(rings));
                        say(sender, "<green>Rotation applied.</green> <gray>Course disabled; enable after editing. All angles accept decimal degrees.");
                    }
                    case "enable" -> {
                        c.enabled(true); // Validate before starting asynchronous final marker capture.
                        plugin.editor().finishAndEnable(id).whenComplete((unused, error) -> reply(sender, error == null
                                ? "Course " + id + " enabled. Center handles removed."
                                : "Could not enable " + id + ": " + error.getMessage()));
                    }
                    case "disable" -> {
                        plugin.store().put(c.enabled(false));
                        sender.sendMessage(plugin.text("<green><course>: disabled.", "course", id));
                    }
                    case "removelast" -> {
                        if (c.rings().isEmpty()) throw new IllegalArgumentException("This course has no rings.");
                        plugin.editor().stop(id); plugin.store().put(c.edit(c.rings().subList(0, c.rings().size() - 1)));
                        say(sender, "<green>Removed the last ring.</green> <gray>Course disabled; leaderboard starts fresh for this layout.");
                    }
                    case "delete" -> {
                        plugin.editor().stop(id); plugin.store().delete(id);
                        say(sender, "<green>Deleted course.</green> <gray>Historical results remain archived in state.yml.");
                    }
                    case "tp" -> {
                        require(args, 3, "/circles tp <course> <number>");
                        if (!(sender instanceof Player p)) throw new IllegalArgumentException("Use this command in-game.");
                        int number = positive(args[2]);
                        if (number > c.rings().size()) throw new IllegalArgumentException("That ring does not exist.");
                        Ring r = c.rings().get(number - 1);
                        var world = plugin.getServer().getWorld(r.world());
                        if (world == null) throw new IllegalArgumentException("That ring's world is not loaded.");
                        Vec at = r.center().sub(r.normal().mul(2)).add(new Vec(0, -.9, 0));
                        p.teleportAsync(new Location(world, at.x(), at.y(), at.z(), r.yaw(), r.pitch())).thenAccept(success -> {
                            if (!success && plugin.isEnabled()) p.getScheduler().run(plugin,
                                    task -> say(p, "<red>Teleport failed."), null);
                        });
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(plugin.text("<red><error>", "error", ex instanceof NumberFormatException ? "Use a valid checkpoint number or radius." : ex.getMessage()));
        }
        return true;
    }
    private void reply(CommandSender sender, String message) {
        if (!plugin.isEnabled()) return;
        if (sender instanceof Player p) p.getScheduler().run(plugin,
                task -> p.sendMessage(net.kyori.adventure.text.Component.text(message)), null);
        else plugin.getServer().getGlobalRegionScheduler().run(plugin,
                task -> sender.sendMessage(net.kyori.adventure.text.Component.text(message)));
    }
    private Course course(String id) {
        Course c = plugin.store().courses().get(id.toLowerCase(Locale.ROOT));
        if (c == null) throw new IllegalArgumentException("Unknown course: " + id);
        return c;
    }
    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("Usage: " + usage);
    }
    private static int positive(String number) {
        int n = Integer.parseInt(number);
        if (n < 1) throw new IllegalArgumentException("Checkpoint numbers start at 1."); return n;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("circles.use")) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) { choices.addAll(PLAY); if (sender.hasPermission("circles.admin")) choices.addAll(ADMIN); }
        if (args.length == 2 && !List.of("create", "help", "leave").contains(args[0].toLowerCase(Locale.ROOT))) choices.addAll(plugin.store().courses().keySet());
        if (args.length == 3 && List.of("set", "setcenter", "rotate", "edit", "tp").contains(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("circles.admin")) {
            Course c = plugin.store().courses().get(args[1]);
            if (c != null) for (int i = 1; i <= c.rings().size() + (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("setcenter") ? 1 : 0); i++) choices.add("" + i);
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.startsWith(prefix)).sorted().toList();
    }
}
