package de.coinshop;

import de.coinshop.ExpeditionManager.Copy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Berechneter Kampf zweier Gruppen (für NPC-Duelle und Untergrund-Arenen). Wie beim Spieler-Duell kämpfen die Ersten
 * beider Seiten gegeneinander, Typen zählen, die Begleiter werden nicht verletzt.
 */
public final class CombatSim {

    private CombatSim() {
    }

    /** Ein Kämpfer mit Typ, Leben und Angriff. */
    public record Fighter(String name, CompanionTypes.MobType type, double hp, double atk, boolean defender, String entity) { }

    /** Ein Abschnitt des Kampfes: ein Kämpfer jeder Seite, Leben (Anteil 0 bis 1) vorher und nachher, wer am Ende fällt. */
    public record Stage(int ia, int ib, double startA, double startB, double endA, double endB, boolean aDies, boolean bDies) { }

    /** Kampfablauf mit Abschnitten (für die Live-Ansicht in der Arena). */
    public record Script(boolean aWins, List<String> log, List<Stage> stages) { }

    private static final java.util.Map<CompanionTypes.MobType, String[]> LOOKS = new java.util.EnumMap<>(CompanionTypes.MobType.class);

    static {
        LOOKS.put(CompanionTypes.MobType.FEUER, new String[]{"MAGMA_CUBE", "PIGLIN_BRUTE", "HOGLIN", "ZOMBIFIED_PIGLIN"});
        LOOKS.put(CompanionTypes.MobType.WASSER, new String[]{"DROWNED", "AXOLOTL", "FROG", "TURTLE"});
        LOOKS.put(CompanionTypes.MobType.NATUR, new String[]{"WOLF", "FOX", "SPIDER", "OCELOT"});
        LOOKS.put(CompanionTypes.MobType.ERDE, new String[]{"IRON_GOLEM", "GOAT", "ARMADILLO", "RAVAGER"});
        LOOKS.put(CompanionTypes.MobType.EIS, new String[]{"POLAR_BEAR", "SNOW_GOLEM", "STRAY"});
        LOOKS.put(CompanionTypes.MobType.LUFT, new String[]{"BREEZE", "CHICKEN", "VEX"});
        LOOKS.put(CompanionTypes.MobType.UNTOTE, new String[]{"SKELETON", "ZOMBIE", "HUSK"});
        LOOKS.put(CompanionTypes.MobType.FINSTER, new String[]{"WITCH", "PILLAGER", "VINDICATOR", "EVOKER"});
    }

    /** Aussehen eines NPC-Kämpfers in der Live-Ansicht (passend zum Typ, je nach Name verschieden). */
    private static String lookOf(CompanionTypes.MobType type, String name) {
        String[] arr = LOOKS.get(type);
        return arr[Math.floorMod(name.hashCode(), arr.length)];
    }

    /** Ergebnis: Sieger (Seite A?) und Kampfbericht. */
    public record Result(boolean aWins, List<String> log) { }

    private static double traitSum(ExpeditionManager ex, UUID owner, Copy k, boolean hp) {
        double t = 1;
        for (CompanionTraits.Trait tr : ex.traits(owner, k.companion(), k.index())) {
            t += hp ? tr.hp : tr.dmg;
        }
        return Math.max(0.3, t);
    }

    /** Kämpfer aus einem echten Begleiter-Exemplar des Spielers "owner". */
    public static Fighter of(ExpeditionManager ex, UUID owner, Copy k) {
        double lb = ex.levelBonus(ex.copyLevel(owner, k.companion(), k.index()));
        double rar = ex.rarity(owner, k.companion(), k.index()).stats;
        double hp = (20 + k.companion().stars * 12.0) * lb * rar * traitSum(ex, owner, k, true) * ex.roleHpFactor(k.companion());
        double atk = (4 + k.companion().stars * 1.5) * lb * rar * traitSum(ex, owner, k, false) * ex.roleDmgFactor(k.companion());
        if (ex.isSupporter(k.companion())) {
            atk = Math.max(1.0, atk * 0.3); // Unterstützer kämpfen kaum, halten aber etwas aus
        }
        return new Fighter(ex.trips().copyName(k.companion(), k.index()), k.companion().mobType(), hp, atk, ex.isDefender(k.companion()), k.companion().type);
    }

    /** Kämpfer eines NPC: Stufe und Sterne bestimmen die Werte wie bei echten Begleitern. */
    public static Fighter npc(ExpeditionManager ex, String name, CompanionTypes.MobType type, int level, int stars, double power) {
        double lb = ex.levelBonus(level);
        double hp = (20 + stars * 12.0) * lb * power;
        double atk = (4 + stars * 1.5) * lb * power;
        return new Fighter(name, type, hp, atk, false, lookOf(type, name));
    }

    /** Gesamtstärke wie bei den Duellen (Wurzel aus der Summe von Leben mal Angriff). */
    public static long strength(List<Fighter> team) {
        double sum = 0;
        for (Fighter f : team) {
            sum += f.hp() * f.atk();
        }
        return Math.round(Math.sqrt(sum));
    }

    /** Berechnet den Kampf. Bei Gleichstand gewinnt Seite B (der Verteidiger). */
    public static Result fight(List<Fighter> a, List<Fighter> b, Random r) {
        Script s = script(a, b, r);
        return new Result(s.aWins(), s.log());
    }

    /** Berechnet den Kampf und merkt sich die Abschnitte (Leben je Abschnitt), damit man ihn live zeigen kann. */
    public static Script script(List<Fighter> a, List<Fighter> b, Random r) {
        List<String> log = new ArrayList<>();
        List<Stage> stages = new ArrayList<>();
        int ia = 0;
        int ib = 0;
        if (a.isEmpty() || b.isEmpty()) {
            return new Script(!a.isEmpty(), log, stages);
        }
        double hpA = a.get(0).hp();
        double hpB = b.get(0).hp();
        double startA = 1.0;
        double startB = 1.0;
        int rounds = 0;
        while (ia < a.size() && ib < b.size() && rounds < 600) {
            rounds++;
            Fighter fa = a.get(ia);
            Fighter fb = b.get(ib);
            double dmgA = fa.atk() * CompanionTypes.factor(fa.type(), fb.type()) * (0.85 + 0.3 * r.nextDouble());
            double dmgB = fb.atk() * CompanionTypes.factor(fb.type(), fa.type()) * (0.85 + 0.3 * r.nextDouble());
            hpB -= dmgA;
            hpA -= dmgB;
            if (hpA <= 0 || hpB <= 0) {
                if (hpA <= 0 && hpB <= 0) {
                    log.add(fa.name() + " und " + fb.name() + " gehen beide zu Boden.");
                } else if (hpB <= 0) {
                    log.add(fa.name() + " besiegt " + fb.name() + ".");
                } else {
                    log.add(fb.name() + " besiegt " + fa.name() + ".");
                }
                stages.add(new Stage(ia, ib, startA, startB, hpA <= 0 ? 0 : hpA / fa.hp(), hpB <= 0 ? 0 : hpB / fb.hp(), hpA <= 0, hpB <= 0));
                startA = hpA <= 0 ? 1.0 : hpA / fa.hp();
                startB = hpB <= 0 ? 1.0 : hpB / fb.hp();
                if (hpA <= 0) {
                    ia++;
                    if (ia < a.size()) {
                        hpA = a.get(ia).hp();
                    }
                }
                if (hpB <= 0) {
                    ib++;
                    if (ib < b.size()) {
                        hpB = b.get(ib).hp();
                    }
                }
            }
        }
        boolean aWins = ia < a.size() && (ib >= b.size() || hpA > hpB);
        if (ia < a.size() && ib < b.size()) {
            // Zeitlimit: der letzte Abschnitt endet, indem der Unterlegene fällt
            Fighter fa = a.get(ia);
            Fighter fb = b.get(ib);
            stages.add(new Stage(ia, ib, startA, startB, Math.max(0.05, hpA / fa.hp()), Math.max(0.05, hpB / fb.hp()), !aWins, aWins));
        }
        return new Script(aWins, log, stages);
    }
}
