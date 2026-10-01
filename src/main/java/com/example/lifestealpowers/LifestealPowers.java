package com.example.lifestealpowers;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class LifestealPowers extends JavaPlugin implements Listener {

    enum Power {
        // active (right-click Power Core)
        DASH("Dash", "Launch yourself forward", false, 8),
        LEAP("Leap", "Jump very high, then float down", false, 12),
        FIREBALL("Fireball", "Shoot a fireball", false, 6),
        LIGHTNING("Lightning", "Strike where you look", false, 20),
        FREEZE("Freeze", "Freeze everyone nearby", false, 25),
        HEAL("Heal", "Regeneration and full food", false, 30),
        TELEPORT("Blink", "Teleport to where you look", false, 10),
        SHOCKWAVE("Shockwave", "Blast nearby entities away", false, 15),
        INVISIBILITY("Vanish", "Become invisible for 10s", false, 40),
        SPEED_BURST("Speed Burst", "Speed III for 10s", false, 25),
        STRENGTH_SURGE("Strength Surge", "Strength II for 10s", false, 35),
        SHIELD("Shield", "Resistance IV + absorption for 8s", false, 40),
        BLINDING_FLASH("Blinding Flash", "Blind nearby players", false, 25),
        WITHER_TOUCH("Wither Touch", "Wither nearby enemies", false, 30),
        VOID_PULL("Void Pull", "Pull entities toward you", false, 20),
        ARROW_STORM("Arrow Storm", "Fire a volley of arrows", false, 20),
        TNT_THROW("TNT Throw", "Throw a primed TNT", false, 15),
        SWAP("Swap", "Swap places with nearest player", false, 30),
        // passive
        NIGHT_EYES("Night Eyes", "Permanent night vision", true, 0),
        WATER_LORD("Water Lord", "Water breathing + dolphin's grace", true, 0),
        FIRE_WALKER("Fire Walker", "Permanent fire resistance", true, 0),
        HASTE_MINER("Haste Miner", "Permanent Haste II", true, 0),
        FEATHER_FALL("Feather Fall", "No fall damage", true, 0),
        LUCKY_CHARM("Lucky Charm", "Permanent Luck II", true, 0),
        // dragon egg powers: only while holding the dragon egg, never handed out or stolen
        DRAGON_FLIGHT("Dragon Flight", "Fly freely (dragon egg holder)", true, 0),
        DRAGON_BREATH("Dragon's Breath", "Breathe a damaging dragon cloud (dragon egg holder)", false, 15);

        final String display, desc;
        final boolean passive;
        final int cooldown;

        Power(String display, String desc, boolean passive, int cooldown) {
            this.display = display; this.desc = desc;
            this.passive = passive; this.cooldown = cooldown;
        }

        boolean eggOnly() { return this == DRAGON_FLIGHT || this == DRAGON_BREATH; }

        /** powers that can be given out, stolen, withdrawn and crafted */
        static List<Power> pool() {
            List<Power> l = new ArrayList<>();
            for (Power p : values()) if (!p.eggOnly()) l.add(p);
            return l;
        }
    }

    // a player can hold several powers (by stealing), but each power has only one owner
    private final Map<UUID, LinkedHashSet<Power>> owned = new HashMap<>();
    private final Set<UUID> lost = new HashSet<>();
    private final Map<UUID, Integer> selected = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Random rng = new Random();
    private File dataFile;
    private NamespacedKey itemKey, eggKey, heartKey, crystalKey, heartRecipeKey, crystalRecipeKey;
    // powers that currently exist only as Power Crystal items (so nobody else is given them)
    private final Set<Power> crystals = new HashSet<>();
    private final Set<UUID> flyers = new HashSet<>();   // players currently flying thanks to the dragon egg

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "power_core");
        eggKey = new NamespacedKey(this, "egg_bonus");
        heartKey = new NamespacedKey(this, "heart_item");
        crystalKey = new NamespacedKey(this, "power_crystal");
        heartRecipeKey = new NamespacedKey(this, "heart_recipe");
        crystalRecipeKey = new NamespacedKey(this, "crystal_recipe");
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        registerRecipes();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    // ---------- config helpers ----------
    private double d(Power pw, String key, double def) {
        return getConfig().getDouble("powers." + pw.name() + "." + key, def);
    }
    private int ticks(Power pw, String key, double defSeconds) { return (int) (d(pw, key, defSeconds) * 20); }
    private int amp(Power pw, String key, int defLevel) { return Math.min(255, Math.max(0, (int) Math.min(d(pw, key, defLevel), 100000) - 1)); }
    private boolean enabled(Power pw) { return getConfig().getBoolean("powers." + pw.name() + ".enabled", true); }
    private int cooldownOf(Power pw) { return getConfig().getInt("powers." + pw.name() + ".cooldown", pw.cooldown); }

    // ---------- helpers ----------
    private Set<Power> powersOf(UUID id) {
        Set<Power> s = owned.get(id);
        return s == null ? Collections.emptySet() : s;
    }

    private List<Power> activesOf(Player pl) {
        List<Power> l = new ArrayList<>();
        for (Power p : powersOf(pl.getUniqueId())) if (!p.passive) l.add(p);
        if (pl.getInventory().contains(Material.DRAGON_EGG) && enabled(Power.DRAGON_BREATH)) l.add(Power.DRAGON_BREATH);
        return l;
    }

    private Set<Power> takenPowers() {
        Set<Power> t = new HashSet<>();
        for (Set<Power> s : owned.values()) t.addAll(s);
        t.addAll(crystals);
        return t;
    }

    // ---------- storage ----------
    private void load() {
        getDataFolder().mkdirs();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        if (y.getConfigurationSection("powers") != null) {
            for (String k : y.getConfigurationSection("powers").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    List<String> names = y.isList("powers." + k)
                            ? y.getStringList("powers." + k)
                            : List.of(String.valueOf(y.getString("powers." + k)));
                    LinkedHashSet<Power> set = new LinkedHashSet<>();
                    for (String n : names) {
                        try { set.add(Power.valueOf(n)); } catch (IllegalArgumentException ignored) {}
                    }
                    if (!set.isEmpty()) owned.put(id, set);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        for (String s : y.getStringList("lost")) {
            try { lost.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        for (String s : y.getStringList("crystals")) {
            try { crystals.add(Power.valueOf(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        owned.forEach((u, set) -> {
            List<String> names = new ArrayList<>();
            for (Power p : set) names.add(p.name());
            y.set("powers." + u, names);
        });
        List<String> l = new ArrayList<>();
        lost.forEach(u -> l.add(u.toString()));
        y.set("lost", l);
        List<String> cl = new ArrayList<>();
        for (Power cp : crystals) cl.add(cp.name());
        y.set("crystals", cl);
        try { y.save(dataFile); } catch (IOException e) { getLogger().warning("Could not save data: " + e.getMessage()); }
    }

    // ---------- power core item ----------
    private ItemStack makeCore() {
        ItemStack s = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName("§dPower Core");
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    private boolean isCore(ItemStack s) {
        return s != null && s.getType() == Material.NETHER_STAR && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void removeCores(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (isCore(s)) p.getInventory().remove(s);
    }

    private void giveCore(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (isCore(s)) return;
        p.getInventory().addItem(makeCore());
    }

    // ---------- heart + power crystal items ----------
    private ItemStack heartItem() {
        ItemStack s = new ItemStack(Material.RED_DYE);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName("§cHeart");
        m.setLore(List.of("§7Right-click to gain 1 heart"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(heartKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    /** pw == null gives a random (mystery) crystal that grants a random free power */
    private ItemStack crystalItem(Power pw) {
        ItemStack s = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(pw == null ? "§bPower Crystal §7(Random)" : "§bPower Crystal: §e" + pw.display);
        m.setLore(List.of(pw == null ? "§7Right-click to gain a random unclaimed power"
                                     : "§7Right-click to gain: " + pw.desc));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(crystalKey, PersistentDataType.STRING, pw == null ? "RANDOM" : pw.name());
        s.setItemMeta(m);
        return s;
    }

    private boolean isHeart(ItemStack s) {
        return s != null && s.getType() == Material.RED_DYE && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(heartKey, PersistentDataType.BYTE);
    }

    private String crystalOf(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(crystalKey, PersistentDataType.STRING);
    }

    private void giveOrDrop(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void consumeOne(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else h.setAmount(h.getAmount() - 1);
    }

    private void useHeart(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        double maxHp = getConfig().getDouble("hearts.max-hearts", 30) * 2;
        if (a.getBaseValue() >= maxHp) { p.sendMessage("§cYou are already at the max hearts."); return; }
        a.setBaseValue(Math.min(maxHp, a.getBaseValue() + 2));
        consumeOne(p);
        p.sendMessage("§aYou gained a heart!");
    }

    private void useCrystal(Player p, String what) {
        UUID id = p.getUniqueId();
        Power pw;
        if (what.equals("RANDOM")) {
            List<Power> free = Power.pool();
            free.removeAll(takenPowers());
            free.removeIf(x -> !enabled(x));
            if (free.isEmpty()) { p.sendMessage("§cThere are no unclaimed powers left."); return; }
            pw = free.get(rng.nextInt(free.size()));
        } else {
            try { pw = Power.valueOf(what); }
            catch (IllegalArgumentException ex) { p.sendMessage("§cThis crystal is broken."); return; }
            crystals.remove(pw);
            for (Set<Power> set : owned.values()) set.remove(pw);   // keep one-of-each
            owned.values().removeIf(Set::isEmpty);
        }
        owned.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(pw);
        lost.remove(id);
        consumeOne(p);
        save();
        giveCore(p);
        p.sendMessage("§6You gained the power: §e" + pw.display + " §7- " + pw.desc);
    }

    private void registerRecipes() {
        registerRecipe("heart", heartRecipeKey, heartItem());
        registerRecipe("power-crystal", crystalRecipeKey, crystalItem(null));
    }

    private void registerRecipe(String id, NamespacedKey key, ItemStack result) {
        Bukkit.removeRecipe(key);
        String base = "recipes." + id;
        if (!getConfig().getBoolean(base + ".enabled", true)) return;
        try {
            List<String> shape = getConfig().getStringList(base + ".shape");
            ConfigurationSection ing = getConfig().getConfigurationSection(base + ".ingredients");
            if (shape.isEmpty() || ing == null) {
                getLogger().warning("Recipe '" + id + "' is missing its shape or ingredients.");
                return;
            }
            ShapedRecipe r = new ShapedRecipe(key, result);
            r.shape(shape.toArray(new String[0]));
            for (String k : ing.getKeys(false)) {
                Material m = Material.matchMaterial(ing.getString(k, ""));
                if (m == null) throw new IllegalArgumentException("unknown material for '" + k + "'");
                r.setIngredient(k.charAt(0), m);
            }
            Bukkit.addRecipe(r);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Could not register recipe '" + id + "': " + ex.getMessage());
        }
    }

    // our special items can't be used as crafting ingredients (e.g. a Power Core as a plain nether star)
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k)) return;
        if (!k.getKey().equals(heartRecipeKey) && !k.getKey().equals(crystalRecipeKey)) return;
        for (ItemStack s : e.getInventory().getMatrix())
            if (isCore(s) || isHeart(s) || crystalOf(s) != null) { e.getInventory().setResult(null); return; }
    }

    // dropped crystals must never be destroyed or despawn (the power would be lost forever)
    @EventHandler
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item it) {
            ItemStack s = it.getItemStack();
            if (isCore(s) || isHeart(s) || crystalOf(s) != null) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent e) {
        String c = crystalOf(e.getEntity().getItemStack());
        if (c != null && !c.equals("RANDOM")) { e.setCancelled(true); e.getEntity().setTicksLived(1); }
    }

    // ---------- assigning ----------
    private void assign(Player p) {
        UUID id = p.getUniqueId();
        if (!powersOf(id).isEmpty()) { giveCore(p); return; }
        if (lost.contains(id)) return;
        List<Power> free = Power.pool();
        free.removeAll(takenPowers());
        free.removeIf(x -> !enabled(x));
        if (free.isEmpty()) { p.sendMessage("§cAll powers are taken."); return; }
        Power pw = free.get(rng.nextInt(free.size()));
        owned.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(pw);
        save();
        giveCore(p);
        p.sendMessage("§6You got the power: §e" + pw.display + " §7- " + pw.desc);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { assign(e.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> assign(p));
    }

    // ---------- death, stealing powers, lifesteal ----------
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        UUID vid = v.getUniqueId();
        e.getDrops().removeIf(this::isCore);
        removeCores(v);

        // the victim loses ONE power (their only one, or a random one if they have several)
        LinkedHashSet<Power> vs = owned.get(vid);
        if (vs != null && !vs.isEmpty()) {
            List<Power> list = new ArrayList<>(vs);
            Power taken = list.get(rng.nextInt(list.size()));
            vs.remove(taken);
            if (vs.isEmpty()) { owned.remove(vid); lost.add(vid); }
            selected.remove(vid);

            if (k != null && !k.equals(v)) {
                // the killer steals it (this also brings a powerless player back)
                owned.computeIfAbsent(k.getUniqueId(), x -> new LinkedHashSet<>()).add(taken);
                lost.remove(k.getUniqueId());
                giveCore(k);
                v.sendMessage("§c" + k.getName() + " stole your power: " + taken.display);
                k.sendMessage("§aYou stole the power: §e" + taken.display + " §7- " + taken.desc);
            } else {
                v.sendMessage("§cYou died and lost your power: " + taken.display);
            }
            save();
        }

        // lifesteal hearts
        if (k != null && !k.equals(v)) {
            AttributeInstance va = v.getAttribute(Attribute.MAX_HEALTH);
            AttributeInstance ka = k.getAttribute(Attribute.MAX_HEALTH);
            double step = getConfig().getDouble("hearts.hearts-per-kill", 1) * 2;
            double minHp = Math.max(1, getConfig().getDouble("hearts.min-hearts", 1)) * 2;   // can't lose the last heart
            double maxHp = getConfig().getDouble("hearts.max-hearts", 30) * 2;
            if (va != null && ka != null && va.getBaseValue() > minHp) {
                va.setBaseValue(Math.max(minHp, va.getBaseValue() - step));
                ka.setBaseValue(Math.min(maxHp, ka.getBaseValue() + step));
                v.sendMessage("§cYou lost a heart.");
                k.sendMessage("§aYou stole a heart!");
            }
        }
    }

    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p
                && powersOf(p.getUniqueId()).contains(Power.FEATHER_FALL) && enabled(Power.FEATHER_FALL)) e.setCancelled(true);
    }

    // ---------- periodic: dragon egg bonus + passive powers ----------
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                boolean has = p.getInventory().contains(Material.DRAGON_EGG);
                AttributeModifier found = null;
                for (AttributeModifier m : a.getModifiers()) if (eggKey.equals(m.getKey())) found = m;
                if (has && found == null)
                    a.addModifier(new AttributeModifier(eggKey, getConfig().getDouble("hearts.dragon-egg-bonus", 20) * 2,
                            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                else if (!has && found != null) a.removeModifier(found);
            }
            boolean flight = p.getInventory().contains(Material.DRAGON_EGG) && enabled(Power.DRAGON_FLIGHT);
            if (flight) {
                flyers.add(p.getUniqueId());
                p.setAllowFlight(true);
                p.setFlySpeed((float) Math.max(0.01, Math.min(1.0, d(Power.DRAGON_FLIGHT, "speed", 0.1))));
            } else if (flyers.remove(p.getUniqueId())) {
                GameMode gm = p.getGameMode();
                if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
                    if (p.isFlying()) eff(p, PotionEffectType.SLOW_FALLING, 200, 0);   // soft landing
                    p.setFlying(false);
                    p.setAllowFlight(false);
                }
                p.setFlySpeed(0.1f);
            }
            for (Power pw : powersOf(p.getUniqueId())) {
                if (!pw.passive || !enabled(pw)) continue;
                switch (pw) {
                    case NIGHT_EYES -> eff(p, PotionEffectType.NIGHT_VISION, 400, 0);
                    case WATER_LORD -> { eff(p, PotionEffectType.WATER_BREATHING, 100, 0); eff(p, PotionEffectType.DOLPHINS_GRACE, 100, 0); }
                    case FIRE_WALKER -> eff(p, PotionEffectType.FIRE_RESISTANCE, 100, 0);
                    case HASTE_MINER -> eff(p, PotionEffectType.HASTE, 100, amp(pw, "level", 2));
                    case LUCKY_CHARM -> eff(p, PotionEffectType.LUCK, 100, amp(pw, "level", 2));
                    default -> {}
                }
            }
        }
    }

    private void eff(LivingEntity e, PotionEffectType t, int ticks, int amp) {
        e.addPotionEffect(new PotionEffect(t, ticks, amp, false, true));
    }

    // ---------- using active powers ----------
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (isHeart(held)) { e.setCancelled(true); useHeart(p); return; }
        String crystal = crystalOf(held);
        if (crystal != null) { e.setCancelled(true); useCrystal(p, crystal); return; }
        if (!isCore(held)) return;
        e.setCancelled(true);

        UUID id = p.getUniqueId();
        List<Power> actives = activesOf(p);
        if (powersOf(id).isEmpty() && actives.isEmpty()) { p.sendMessage("§cYou have no power. Kill someone to steal one!"); return; }
        if (actives.isEmpty()) { p.sendMessage("§eYour powers are all passive and always active."); return; }

        int idx = selected.getOrDefault(id, 0) % actives.size();

        // sneak + right-click = switch between your active powers
        if (p.isSneaking()) {
            idx = (idx + 1) % actives.size();
            selected.put(id, idx);
            p.sendMessage("§6Selected power: §e" + actives.get(idx).display);
            return;
        }

        Power pw = actives.get(idx);
        if (!enabled(pw)) { p.sendMessage("§c" + pw.display + " is disabled on this server."); return; }
        String key = id + ":" + pw.name();
        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(key, 0L);
        if (now < ready) {
            p.sendMessage("§c" + pw.display + " cooldown: " + ((ready - now) / 1000 + 1) + "s");
            return;
        }
        if (use(p, pw)) cooldowns.put(key, now + cooldownOf(pw) * 1000L);
    }

    private List<LivingEntity> nearby(Player p, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r))
            if (e instanceof LivingEntity le && !e.equals(p)) out.add(le);
        return out;
    }

    private boolean use(Player p, Power pw) {
        Vector dir = p.getLocation().getDirection();
        World w = p.getWorld();
        switch (pw) {
            case DASH -> p.setVelocity(dir.clone().multiply(d(pw, "strength", 2.2)).setY(d(pw, "lift", 0.4)));
            case LEAP -> {
                p.setVelocity(new Vector(0, d(pw, "height", 1.6), 0));
                eff(p, PotionEffectType.SLOW_FALLING, ticks(pw, "slow-fall-seconds", 5), 0);
            }
            case FIREBALL -> {
                Fireball f = p.launchProjectile(Fireball.class);
                f.setYield((float) d(pw, "explosion-power", 1));
            }
            case LIGHTNING -> {
                double range = d(pw, "range", 40);
                RayTraceResult r = p.rayTraceBlocks(range);
                Location l = r != null ? r.getHitPosition().toLocation(w)
                        : p.getEyeLocation().add(dir.clone().multiply(range));
                w.strikeLightning(l);
            }
            case FREEZE -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 8))) {
                    e.setFreezeTicks(ticks(pw, "duration", 5) * 2);
                    eff(e, PotionEffectType.SLOWNESS, ticks(pw, "duration", 5), amp(pw, "level", 5));
                }
            }
            case HEAL -> { eff(p, PotionEffectType.REGENERATION, ticks(pw, "duration", 10), amp(pw, "level", 3)); p.setFoodLevel(20); }
            case TELEPORT -> {
                RayTraceResult r = p.rayTraceBlocks(d(pw, "range", 30));
                if (r == null || r.getHitBlockFace() == null) { p.sendMessage("§cNothing to blink to."); return false; }
                Location l = r.getHitPosition().toLocation(w).add(r.getHitBlockFace().getDirection());
                l.setYaw(p.getLocation().getYaw()); l.setPitch(p.getLocation().getPitch());
                p.teleport(l);
            }
            case SHOCKWAVE -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 6))) {
                    Vector v = e.getLocation().toVector().subtract(p.getLocation().toVector());
                    if (v.lengthSquared() < 0.01) continue;
                    e.setVelocity(v.normalize().multiply(d(pw, "strength", 2)).setY(0.6));
                }
            }
            case INVISIBILITY -> eff(p, PotionEffectType.INVISIBILITY, ticks(pw, "duration", 10), 0);
            case SPEED_BURST -> eff(p, PotionEffectType.SPEED, ticks(pw, "duration", 10), amp(pw, "level", 3));
            case STRENGTH_SURGE -> eff(p, PotionEffectType.STRENGTH, ticks(pw, "duration", 10), amp(pw, "level", 2));
            case SHIELD -> {
                eff(p, PotionEffectType.RESISTANCE, ticks(pw, "duration", 8), amp(pw, "resistance-level", 4));
                eff(p, PotionEffectType.ABSORPTION, ticks(pw, "duration", 8), amp(pw, "absorption-level", 3));
            }
            case BLINDING_FLASH -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 10)))
                    if (e instanceof Player) eff(e, PotionEffectType.BLINDNESS, ticks(pw, "duration", 5), 0);
            }
            case WITHER_TOUCH -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 6)))
                    eff(e, PotionEffectType.WITHER, ticks(pw, "duration", 5), amp(pw, "level", 2));
            }
            case VOID_PULL -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 12))) {
                    Vector v = p.getLocation().toVector().subtract(e.getLocation().toVector());
                    if (v.lengthSquared() < 1) continue;
                    e.setVelocity(v.normalize().multiply(d(pw, "strength", 1.4)).setY(0.3));
                }
            }
            case ARROW_STORM -> {
                int n = (int) d(pw, "arrows", 12);
                for (int i = 0; i < n; i++) {
                    Arrow a = p.launchProjectile(Arrow.class);
                    Vector spread = new Vector(rng.nextDouble() - .5, rng.nextDouble() * .3, rng.nextDouble() - .5).multiply(.4);
                    a.setVelocity(dir.clone().add(spread).multiply(d(pw, "speed", 2.5)));
                }
            }
            case TNT_THROW -> {
                TNTPrimed t = w.spawn(p.getEyeLocation(), TNTPrimed.class);
                t.setVelocity(dir.clone().multiply(d(pw, "speed", 1.5)));
                t.setFuseTicks(ticks(pw, "fuse-seconds", 2));
            }
            case SWAP -> {
                double range = d(pw, "range", 25);
                Player best = null; double bd = range * range;
                for (Player o : w.getPlayers()) {
                    if (o.equals(p)) continue;
                    double dist = o.getLocation().distanceSquared(p.getLocation());
                    if (dist < bd) { bd = dist; best = o; }
                }
                if (best == null) { p.sendMessage("§cNo player within " + (int) range + " blocks."); return false; }
                Location a = p.getLocation(), b = best.getLocation();
                p.teleport(b); best.teleport(a);
            }
            case DRAGON_BREATH -> {
                double range = d(pw, "range", 8), radius = d(pw, "radius", 4), dmg = d(pw, "damage", 2);
                int dur = ticks(pw, "duration", 8);
                RayTraceResult r = p.rayTraceBlocks(range);
                Location center = r != null ? r.getHitPosition().toLocation(w)
                        : p.getEyeLocation().add(dir.clone().multiply(range));
                AreaEffectCloud cloud = w.spawn(center, AreaEffectCloud.class);
                cloud.setColor(Color.fromRGB(0xA020F0));
                cloud.setRadius((float) radius);
                cloud.setRadiusPerTick(0f);
                cloud.setDuration(dur);
                // we do the damage ourselves so the owner is never hurt by their own cloud
                new BukkitRunnable() {
                    int t = 0;
                    @Override public void run() {
                        if (t >= dur || cloud.isDead()) { cancel(); return; }
                        for (Entity en : w.getNearbyEntities(center, radius, radius, radius))
                            if (en instanceof LivingEntity le && !le.equals(p)) le.damage(dmg, p);
                        t += 10;
                    }
                }.runTaskTimer(this, 0L, 10L);
            }
            default -> { return false; }
        }
        return true;
    }

    // ---------- /powers ----------
    private boolean withdrawHeart(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (!getConfig().getBoolean("withdraw.hearts", true)) { p.sendMessage("§cWithdrawing hearts is disabled."); return true; }
        int n = 1;
        if (a.length > 0) {
            try { n = Integer.parseInt(a[0]); } catch (NumberFormatException ex) { return false; }
            if (n < 1) return false;
        }
        AttributeInstance at = p.getAttribute(Attribute.MAX_HEALTH);
        if (at == null) return true;
        double minHp = Math.max(1, getConfig().getDouble("hearts.min-hearts", 1)) * 2;   // can't withdraw your last heart
        int can = (int) ((at.getBaseValue() - minHp) / 2);
        if (can < 1) { p.sendMessage("§cYou can't withdraw your last heart."); return true; }
        n = Math.min(Math.min(n, can), 64);
        at.setBaseValue(at.getBaseValue() - n * 2);
        ItemStack h = heartItem();
        h.setAmount(n);
        giveOrDrop(p, h);
        p.sendMessage("§aYou withdrew " + n + " heart" + (n == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean withdrawPower(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (!getConfig().getBoolean("withdraw.powers", true)) { p.sendMessage("§cWithdrawing powers is disabled."); return true; }
        UUID id = p.getUniqueId();
        Set<Power> ps = owned.get(id);
        if (ps == null || ps.isEmpty()) { p.sendMessage("§cYou have no power to withdraw."); return true; }
        if (a.length < 1) {
            StringBuilder sb = new StringBuilder();
            for (Power pw : ps) sb.append(pw.name()).append(" ");
            p.sendMessage("§6Usage: /withdrawpower <power>  §7Your powers: " + sb);
            return true;
        }
        Power pw;
        try { pw = Power.valueOf(a[0].toUpperCase()); }
        catch (IllegalArgumentException ex) { p.sendMessage("§cUnknown power. Use /powers to see yours."); return true; }
        if (!ps.remove(pw)) { p.sendMessage("§cYou don't own that power."); return true; }
        if (ps.isEmpty()) { owned.remove(id); lost.add(id); }
        selected.remove(id);
        crystals.add(pw);
        save();
        giveOrDrop(p, crystalItem(pw));
        p.sendMessage("§aYou withdrew " + pw.display + " into a Power Crystal.");
        return true;
    }


    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String cn = c.getName().toLowerCase();
        if (cn.equals("withdrawheart")) return withdrawHeart(s, a);
        if (cn.equals("withdrawpower")) return withdrawPower(s, a);
        if (a.length == 0) {
            if (!(s instanceof Player p)) return true;
            Set<Power> ps = powersOf(p.getUniqueId());
            boolean egg = p.getInventory().contains(Material.DRAGON_EGG);
            if (ps.isEmpty() && !egg) { p.sendMessage("§cYou have no power. Kill someone to steal one!"); return true; }
            for (Power pw : ps) p.sendMessage("§6" + pw.display + " §7- " + pw.desc + (pw.passive ? " §8(passive)" : ""));
            if (egg) p.sendMessage("§5Dragon egg powers: §dDragon Flight §7(passive) §dand Dragon's Breath §7(active) - while you hold the egg");
            p.sendMessage("§7Sneak + right-click the Power Core to switch active powers.");
            return true;
        }
        if (a[0].equalsIgnoreCase("list")) {
            for (Power pw : Power.values()) {
                String holder = pw.eggOnly() ? "dragon egg holder" : "free";
                for (var en : owned.entrySet()) if (en.getValue().contains(pw)) {
                    String n = Bukkit.getOfflinePlayer(en.getKey()).getName();
                    holder = n == null ? en.getKey().toString() : n;
                }
                s.sendMessage("§e" + pw.name() + " §7(" + pw.display + ") - " + holder);
            }
            return true;
        }
        if (!s.isOp()) { s.sendMessage("§cAdmins only."); return true; }
        if (a[0].equalsIgnoreCase("reload")) { reloadConfig(); registerRecipes(); s.sendMessage("§aConfig reloaded."); return true; }
        if (a.length < 2) return false;
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage("§cPlayer not online."); return true; }
        UUID tid = t.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "reset" -> { owned.remove(tid); selected.remove(tid); removeCores(t); save(); s.sendMessage("§aReset."); }
            case "revive" -> { lost.remove(tid); save(); assign(t); s.sendMessage("§aRevived."); }
            case "give" -> {
                if (a.length < 3) return false;
                try {
                    Power pw = Power.valueOf(a[2].toUpperCase());
                    if (pw.eggOnly()) { s.sendMessage("§cDragon egg powers can't be given. They come from holding the egg."); return true; }
                    for (Set<Power> set : owned.values()) set.remove(pw);   // keep one-of-each
                    owned.values().removeIf(Set::isEmpty);
                    owned.computeIfAbsent(tid, x -> new LinkedHashSet<>()).add(pw);
                    crystals.remove(pw);
                    lost.remove(tid);
                    save(); giveCore(t);
                    s.sendMessage("§aGiven " + pw.display + ".");
                } catch (IllegalArgumentException ex) { s.sendMessage("§cUnknown power. Use /powers list"); }
            }
            case "item" -> {
                if (a.length < 3) return false;
                if (a[2].equalsIgnoreCase("heart")) giveOrDrop(t, heartItem());
                else if (a[2].equalsIgnoreCase("crystal")) giveOrDrop(t, crystalItem(null));
                else return false;
                s.sendMessage("§aGiven.");
            }
            default -> { return false; }
        }
        return true;
    }
}
