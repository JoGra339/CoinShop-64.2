package de.coinshop;

import de.coinshop.CombatSim.Fighter;
import de.coinshop.CombatSim.Script;
import de.coinshop.CombatSim.Stage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live-Ansicht für Duelle und Untergrund-Arenen: Die Zuschauer werden in eine Kampfbühne gebracht und sehen, wie die
 * Kreaturen beider Seiten gegeneinander antreten. Der Ausgang steht vorher fest (er wird berechnet wie bisher); die
 * Kreaturen sind nur die Darsteller. Danach geht es zurück und das Ergebnis wird ausgezahlt.
 */
public final class LiveFight implements Listener {

    private static final int HALF = 16;
    private static final int STAGE_TICKS = 120;
    private static final int INTRO_TICKS = 60;
    private static final int OUTRO_TICKS = 80;
    private static final int STEP = 10;

    private static final Material[][] THEMES = {
            {Material.STONE_BRICKS, Material.POLISHED_ANDESITE},
            {Material.DEEPSLATE_TILES, Material.POLISHED_DEEPSLATE},
            {Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE},
            {Material.NETHER_BRICKS, Material.RED_NETHER_BRICKS},
            {Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE},
            {Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE},
            {Material.MOSSY_STONE_BRICKS, Material.MOSS_BLOCK}
    };

    /** Kreaturen, die auf einer Bühne nichts taugen (fliegen, schwimmen oder sind Bosse): sie werden durch einen Zombie ersetzt. */
    private static final Set<String> UNSUITED = Set.of("BAT", "GHAST", "HAPPY_GHAST", "PHANTOM", "ALLAY", "PARROT", "WITHER",
            "ENDER_DRAGON", "ELDER_GUARDIAN", "GUARDIAN", "COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH", "SQUID", "GLOW_SQUID",
            "TADPOLE", "DOLPHIN", "NAUTILUS", "ZOMBIE_NAUTILUS", "BLAZE", "STRIDER", "WARDEN", "SHULKER", "ENDERMAN", "ENDERMITE");

    private final CoinShopPlugin plugin;
    private final ExpeditionManager ex;
    private final Map<UUID, Show> watching = new HashMap<>();
    private final Set<UUID> actors = new HashSet<>();
    private final Set<Integer> usedSlots = new HashSet<>();
    private final Map<Integer, Integer> builtTheme = new HashMap<>();
    private boolean registered;

    LiveFight(CoinShopPlugin plugin, ExpeditionManager ex) {
        this.plugin = plugin;
        this.ex = ex;
    }

    /** Sieht dieser Spieler gerade einem Kampf zu? */
    public boolean watching(UUID uuid) {
        return watching.containsKey(uuid);
    }

    private static final class Viewer {
        final UUID id;
        Location back;
        GameMode mode;

        Viewer(UUID id) {
            this.id = id;
        }
    }

    private final class Show {
        final int slot;
        final List<Viewer> viewers = new ArrayList<>();
        final List<LivingEntity> sideA = new ArrayList<>();
        final List<LivingEntity> sideB = new ArrayList<>();
        final List<Fighter> fa;
        final List<Fighter> fb;
        final Script script;
        final Runnable after;
        final Location center;
        final String title;
        int tick;
        int stageNo = -1;
        boolean ended;
        BukkitTask task;

        Show(int slot, List<Fighter> fa, List<Fighter> fb, Script script, Runnable after, Location center, String title) {
            this.slot = slot;
            this.fa = fa;
            this.fb = fb;
            this.script = script;
            this.after = after;
            this.center = center;
            this.title = title;
        }
    }

    /**
     * Zeigt den Kampf. viewers sind die Zuschauer (bei einem Spieler-Duell beide Spieler). Ist die Bühne nicht
     * verfügbar, wird gleich ohne Anzeige fortgefahren. after wird am Ende ausgeführt.
     */
    public void start(List<Player> viewers, String title, int theme, List<Fighter> a, List<Fighter> b, Script script, Runnable after) {
        World world = ex.showWorld();
        if (world == null || script.stages().isEmpty() || viewers.isEmpty()) {
            after.run();
            return;
        }
        if (!registered) {
            registered = true;
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
        int slot = 0;
        while (usedSlots.contains(slot)) {
            slot++;
        }
        usedSlots.add(slot);
        int cx = 40000;
        int cz = 40000 + slot * 100;
        int y = ex.showY();
        build(world, cx, cz, y, Math.floorMod(theme, THEMES.length), slot);
        Show s = new Show(slot, a, b, script, after, new Location(world, cx + 0.5, y + 1, cz + 0.5), title);
        for (Player p : viewers) {
            Viewer v = new Viewer(p.getUniqueId());
            v.back = p.getLocation().clone();
            v.mode = p.getGameMode();
            s.viewers.add(v);
            watching.put(p.getUniqueId(), s);
            p.closeInventory();
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(viewPoint(s));
            p.showTitle(Title.title(Component.text(title, NamedTextColor.GOLD),
                    Component.text("Du siehst zu - gleich geht es los", NamedTextColor.GRAY)));
        }
        spawnAll(s, world);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(s), STEP, STEP);
    }

    private Location viewPoint(Show s) {
        Location l = s.center.clone().add(0, 5, -(HALF + 2));
        l.setYaw(0f);
        l.setPitch(25f);
        return l;
    }

    // ------------------------------------------------------------------
    //  Bühne
    // ------------------------------------------------------------------

    private void build(World w, int cx, int cz, int y, int theme, int slot) {
        Integer built = builtTheme.get(slot);
        if (built != null && built == theme) {
            return;
        }
        Material floor = THEMES[theme][0];
        Material floor2 = THEMES[theme][1];
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                boolean edge = Math.abs(dx) == HALF || Math.abs(dz) == HALF;
                boolean lamp = dx % 8 == 0 && dz % 8 == 0;
                Material m = lamp ? Material.SEA_LANTERN : edge || (dx + dz) % 2 == 0 ? floor2 : floor;
                w.getBlockAt(cx + dx, y, cz + dz).setType(m, false);
                for (int h = 1; h <= 7; h++) {
                    Material up = Material.AIR;
                    if (edge && (h <= 6)) {
                        up = h <= 6 && h >= 2 ? Material.BARRIER : (h == 1 ? floor2 : Material.AIR);
                    }
                    w.getBlockAt(cx + dx, y + h, cz + dz).setType(up, false);
                }
            }
        }
        builtTheme.put(slot, theme);
    }

    private void spawnAll(Show s, World world) {
        spawnSide(s, world, s.fa, s.sideA, -9, NamedTextColor.GREEN);
        spawnSide(s, world, s.fb, s.sideB, 9, NamedTextColor.RED);
    }

    private void spawnSide(Show s, World world, List<Fighter> list, List<LivingEntity> out, int dx, NamedTextColor color) {
        int n = list.size();
        double spacing = n <= 1 ? 0 : Math.min(3.0, (HALF * 2 - 6.0) / (n - 1));
        for (int i = 0; i < n; i++) {
            Fighter f = list.get(i);
            double dz = n <= 1 ? 0 : (i - (n - 1) / 2.0) * spacing;
            Location at = s.center.clone().add(dx, 0, dz);
            at.setYaw(dx < 0 ? -90f : 90f);
            LivingEntity e = spawnOne(world, at, f);
            out.add(e);
            if (e != null) {
                actors.add(e.getUniqueId());
                label(e, f, color, 1.0);
                if (e instanceof Mob m) {
                    m.setAware(false);
                }
            }
        }
    }

    private LivingEntity spawnOne(World world, Location at, Fighter f) {
        String name = f.entity();
        if (name == null || UNSUITED.contains(name)) {
            name = "ZOMBIE";
        }
        for (String tryName : new String[]{name, "ZOMBIE"}) {
            try {
                Entity e = ex.spawnShow(at, EntityType.valueOf(tryName));
                if (e instanceof LivingEntity le) {
                    AttributeInstance max = le.getAttribute(Attribute.MAX_HEALTH);
                    if (max != null) {
                        max.setBaseValue(1000);
                        le.setHealth(1000);
                    }
                    AttributeInstance atk = le.getAttribute(Attribute.ATTACK_DAMAGE);
                    if (atk != null) {
                        atk.setBaseValue(1.0);
                    }
                    le.setRemoveWhenFarAway(false);
                    le.setPersistent(false);
                    if (le instanceof org.bukkit.entity.Ageable ag && !ag.isAdult()) {
                        ag.setAdult();
                    }
                    return le;
                }
                e.remove();
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // nächste Möglichkeit
            }
        }
        return null;
    }

    private void label(LivingEntity e, Fighter f, NamedTextColor color, double frac) {
        int pct = (int) Math.max(1, Math.round(frac * 100));
        NamedTextColor hp = pct > 60 ? NamedTextColor.GREEN : pct > 30 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        e.customName(Component.text(f.name() + " ", color).append(Component.text("❤ " + pct + " %", hp)));
        e.setCustomNameVisible(true);
    }

    // ------------------------------------------------------------------
    //  Ablauf
    // ------------------------------------------------------------------

    private void step(Show s) {
        if (s.ended) {
            return;
        }
        s.tick += STEP;
        // Zuschauer prüfen
        s.viewers.removeIf(v -> {
            Player p = Bukkit.getPlayer(v.id);
            if (p == null || !p.isOnline()) {
                return true;
            }
            if (!p.getWorld().equals(s.center.getWorld()) || p.getLocation().distanceSquared(s.center) > 45 * 45) {
                p.teleport(viewPoint(s));
            }
            return false;
        });
        if (s.viewers.isEmpty()) {
            finish(s);
            return;
        }
        int total = INTRO_TICKS + s.script.stages().size() * STAGE_TICKS + OUTRO_TICKS;
        if (s.tick > total + 400) {
            finish(s);
            return;
        }
        if (s.tick < INTRO_TICKS) {
            return;
        }
        int fightTick = s.tick - INTRO_TICKS;
        int no = fightTick / STAGE_TICKS;
        if (no >= s.script.stages().size()) {
            if (s.tick >= total) {
                finish(s);
            } else if (s.stageNo != Integer.MAX_VALUE) {
                s.stageNo = Integer.MAX_VALUE;
                String text = s.script.aWins() ? "Seite A siegt" : "Seite B siegt";
                for (Viewer v : s.viewers) {
                    Player p = Bukkit.getPlayer(v.id);
                    if (p != null) {
                        p.sendActionBar(Component.text(text, NamedTextColor.GOLD));
                    }
                }
            }
            return;
        }
        Stage st = s.script.stages().get(no);
        if (no != s.stageNo) {
            s.stageNo = no;
            beginStage(s, st);
        }
        double t = Math.min(1.0, (fightTick % STAGE_TICKS) / (double) (STAGE_TICKS - STEP));
        LivingEntity ea = entity(s.sideA, st.ia());
        LivingEntity eb = entity(s.sideB, st.ib());
        if (ea != null) {
            label(ea, s.fa.get(st.ia()), NamedTextColor.GREEN, lerp(st.startA(), st.endA(), t));
        }
        if (eb != null) {
            label(eb, s.fb.get(st.ib()), NamedTextColor.RED, lerp(st.startB(), st.endB(), t));
        }
        fightStep(ea, eb);
        fightStep(eb, ea);
        if (fightTick % STAGE_TICKS >= STAGE_TICKS - STEP) {
            if (st.aDies()) {
                knockOut(ea, s.sideA, st.ia());
            }
            if (st.bDies()) {
                knockOut(eb, s.sideB, st.ib());
            }
        }
        for (Viewer v : s.viewers) {
            Player p = Bukkit.getPlayer(v.id);
            if (p != null) {
                p.sendActionBar(Component.text(s.title + ": Kampf " + (no + 1) + " von " + s.script.stages().size(), NamedTextColor.GOLD));
            }
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static LivingEntity entity(List<LivingEntity> list, int i) {
        if (i < 0 || i >= list.size()) {
            return null;
        }
        LivingEntity e = list.get(i);
        return e != null && e.isValid() && !e.isDead() ? e : null;
    }

    private void beginStage(Show s, Stage st) {
        LivingEntity ea = entity(s.sideA, st.ia());
        LivingEntity eb = entity(s.sideB, st.ib());
        for (LivingEntity e : new LivingEntity[]{ea, eb}) {
            if (e instanceof Mob m) {
                m.setAware(true);
            }
        }
        // die beiden Kämpfer treten in die Mitte
        if (ea != null) {
            ea.teleport(s.center.clone().add(-3, 0, 0));
        }
        if (eb != null) {
            eb.teleport(s.center.clone().add(3, 0, 0));
        }
    }

    private void fightStep(LivingEntity self, LivingEntity foe) {
        if (self == null || foe == null) {
            return;
        }
        if (self instanceof Mob m) {
            m.setTarget(foe);
            try {
                m.getPathfinder().moveTo(foe.getLocation());
            } catch (RuntimeException ignored) {
                // manche Kreaturen können nicht laufen
            }
        }
        if (self.getLocation().distanceSquared(foe.getLocation()) < 12) {
            self.swingMainHand();
            foe.damage(1.0, self);
        }
    }

    private void knockOut(LivingEntity e, List<LivingEntity> side, int index) {
        if (e == null) {
            return;
        }
        Location at = e.getLocation().add(0, 1, 0);
        at.getWorld().strikeLightningEffect(at);
        at.getWorld().spawnParticle(Particle.POOF, at, 25, 0.4, 0.6, 0.4, 0.02);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        actors.remove(e.getUniqueId());
        e.remove();
        side.set(index, null);
    }

    private void finish(Show s) {
        if (s.ended) {
            return;
        }
        s.ended = true;
        if (s.task != null) {
            s.task.cancel();
        }
        for (List<LivingEntity> side : List.of(s.sideA, s.sideB)) {
            for (LivingEntity e : side) {
                if (e != null) {
                    actors.remove(e.getUniqueId());
                    e.remove();
                }
            }
        }
        usedSlots.remove(s.slot);
        for (Viewer v : s.viewers) {
            Player p = Bukkit.getPlayer(v.id);
            watching.remove(v.id);
            if (p != null && p.isOnline()) {
                restore(p, v);
            }
        }
        for (Map.Entry<UUID, Show> e : new ArrayList<>(watching.entrySet())) {
            if (e.getValue() == s) {
                watching.remove(e.getKey());
            }
        }
        try {
            s.after.run();
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Live-Kampf: Auswertung fehlgeschlagen: " + e.getMessage());
        }
    }

    private void restore(Player p, Viewer v) {
        p.setGameMode(v.mode == null || v.mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : v.mode);
        if (v.back != null && v.back.getWorld() != null) {
            p.teleport(v.back);
        }
    }

    /** Server stoppt: alle Zuschauer zurückbringen, Darsteller entfernen (Ergebnisse werden dabei nicht ausgezahlt). */
    public void abortAll() {
        for (Show s : new ArrayList<>(new HashSet<>(watching.values()))) {
            if (s.ended) {
                continue;
            }
            s.ended = true;
            if (s.task != null) {
                s.task.cancel();
            }
            for (List<LivingEntity> side : List.of(s.sideA, s.sideB)) {
                for (LivingEntity e : side) {
                    if (e != null) {
                        e.remove();
                    }
                }
            }
            for (Viewer v : s.viewers) {
                Player p = Bukkit.getPlayer(v.id);
                if (p != null) {
                    restore(p, v);
                }
            }
        }
        watching.clear();
        actors.clear();
        usedSlots.clear();
    }

    // ------------------------------------------------------------------
    //  Ereignisse
    // ------------------------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Show s = watching.remove(p.getUniqueId());
        if (s == null) {
            return;
        }
        for (Viewer v : new ArrayList<>(s.viewers)) {
            if (v.id.equals(p.getUniqueId())) {
                restore(p, v);
                s.viewers.remove(v);
            }
        }
        if (s.viewers.isEmpty()) {
            finish(s);
        }
    }

    /** Falls der Server mitten im Kampf abstürzte: Wer in der Kampfwelt steht und nirgends mitmacht, wird zurückgeschickt. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World w = ex.showWorld();
            if (p.isOnline() && w != null && p.getWorld().equals(w) && !ex.isIn(p.getUniqueId()) && !watching.containsKey(p.getUniqueId())
                    && p.getLocation().getX() > 30000) {
                p.setGameMode(GameMode.SURVIVAL);
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(EntityDeathEvent event) {
        if (actors.contains(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        if (actors.contains(event.getEntity().getUniqueId())) {
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onPrime(ExplosionPrimeEvent event) {
        if (actors.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
