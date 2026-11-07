package dev.circles;

import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.meta.FireworkMeta;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceEffectsTest {
    @Test void checkpointPlaysASoftXpPickupAtThePlayer() {
        List<Object[]> sounds = new ArrayList<>(); Location location = new Location(null, 1, 70, 2);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p,m,args) -> {
            if (m.getName().equals("getLocation")) return location;
            if (m.getName().equals("playSound")) { sounds.add(args); return null; }
            throw new AssertionError("Unexpected player access: " + m.getName());
        });
        RaceEffects.checkpoint(player);
        assertEquals(1, sounds.size()); assertEquals(location, sounds.getFirst()[0]);
        assertEquals("minecraft:entity.experience_orb.pickup", sounds.getFirst()[1]);
        assertEquals(.25f, sounds.getFirst()[2]); assertEquals(1.3f, sounds.getFirst()[3]);
    }
    @Test void finishRocketIsShortLivedAndHasGreenGoldExplosion() {
        Map<String,Object[]> calls = new HashMap<>();
        FireworkMeta meta = (FireworkMeta) Proxy.newProxyInstance(FireworkMeta.class.getClassLoader(), new Class<?>[]{FireworkMeta.class},
                (p,m,args) -> { calls.put("meta."+m.getName(),args); return null; });
        Firework rocket = (Firework) Proxy.newProxyInstance(Firework.class.getClassLoader(), new Class<?>[]{Firework.class}, (p,m,args) -> {
            if (m.getName().equals("getFireworkMeta")) return meta;
            calls.put(m.getName(),args); return null;
        });
        RaceEffects.configure(rocket);
        assertEquals(false, calls.get("setPersistent")[0]); assertEquals(10, calls.get("setTicksToDetonate")[0]);
        assertEquals(0, calls.get("meta.setPower")[0]); assertSame(meta, calls.get("setFireworkMeta")[0]);
        FireworkEffect effect = (FireworkEffect) calls.get("meta.addEffect")[0];
        assertEquals(FireworkEffect.Type.BALL, effect.getType());
        assertEquals(List.of(Color.LIME,Color.YELLOW), effect.getColors()); assertTrue(effect.hasTrail());
    }
    @Test void finishRocketDamageIsCancelledWithoutReadingForeignEntityState() {
        UUID rocketId = UUID.randomUUID();
        Entity rocket = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class}, (p,m,args) -> {
            if (m.getName().equals("getUniqueId")) return rocketId;
            throw new AssertionError("Unsafe damage-source access: " + m.getName());
        });
        Entity target = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class}, (p,m,args) -> null);
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(), new Class<?>[]{DamageSource.class}, (p,m,args) -> null);
        var modifiers = new EnumMap<EntityDamageEvent.DamageModifier,Double>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 5.0);
        var functions = new EnumMap<EntityDamageEvent.DamageModifier,com.google.common.base.Function<? super Double,Double>>(EntityDamageEvent.DamageModifier.class);
        functions.put(EntityDamageEvent.DamageModifier.BASE, damage -> 0.0);
        var event = new EntityDamageByEntityEvent(rocket,target,EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,source,modifiers,functions,false);
        new RaceEffects(null,Set.of(rocketId)).damage(event); assertTrue(event.isCancelled());
        event.setCancelled(false);
        new RaceEffects(null,Set.of()).damage(event); assertFalse(event.isCancelled(),"Unrelated fireworks/damage must remain untouched");
    }
}
