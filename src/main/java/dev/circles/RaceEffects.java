package dev.circles;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

/** Called from the racer's region. The rocket subsequently uses its own entity scheduler. */
final class RaceEffects implements Listener {
    private final JavaPlugin plugin;
    private final Set<UUID> finishFireworks;
    RaceEffects(JavaPlugin plugin) { this(plugin, ConcurrentHashMap.newKeySet()); }
    RaceEffects(JavaPlugin plugin, Set<UUID> finishFireworks) { this.plugin = plugin; this.finishFireworks = finishFireworks; }
    static void checkpoint(Player player) {
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", .25f, 1.3f);
    }
    static void configure(Firework rocket) {
        rocket.setPersistent(false);
        FireworkMeta meta = rocket.getFireworkMeta();
        meta.clearEffects(); meta.setPower(0);
        meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(Color.LIME, Color.YELLOW)
                .withFade(Color.WHITE).trail(true).build());
        rocket.setFireworkMeta(meta);
        rocket.setShotAtAngle(false);
        rocket.setTicksFlown(0); rocket.setTicksToDetonate(10);
    }
    void finish(Player player) {
        Location at = player.getLocation().add(0, 1.5, 0);
        Firework rocket = at.getWorld().spawn(at, Firework.class, entity -> {
            finishFireworks.add(entity.getUniqueId()); configure(entity);
        });
        UUID uuid = rocket.getUniqueId();
        // Cleanup also covers rockets removed/cancelled by another plugin before exploding.
        var task = rocket.getScheduler().runDelayed(plugin, scheduled -> {
            if (rocket.isValid()) rocket.remove(); cleanup(uuid);
        }, () -> cleanup(uuid), 100);
        if (task == null) cleanup(uuid);
    }
    private void cleanup(UUID uuid) {
        if (!plugin.isEnabled()) { finishFireworks.remove(uuid); return; }
        // Retain the UUID briefly after removal so explosion damage can still be identified.
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> finishFireworks.remove(uuid), 1, TimeUnit.SECONDS);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        // Only read immutable UUIDs here: a Folia damage target may be in a different region.
        if (finishFireworks.contains(event.getDamager().getUniqueId())) event.setCancelled(true);
    }
}
