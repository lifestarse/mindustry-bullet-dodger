// Build: 1
package dodger;

import mindustry.world.Block;

import java.util.Set;

/**
 * Классификация турелей: какие баитим, от каких бежим, какие игнорим.
 * Решение по имени блока — переживает рефакторинги классов в Mindustry.
 */
public final class TurretCatalog {

    public enum Kind { BAITABLE, DEATH, IGNORE }

    // Турели, которые мы хотим заставить тратить ammo на нас.
    // Только прямые баллистические снаряды (включая homing с power<=0.15).
    private static final Set<String> BAIT = Set.of(
        "duo", "salvo", "hail", "scorch", "wave"
    );

    // Турели, в радиус которых заходить нельзя (одна попытка = смерть).
    // Beam (мгновенный урон), splash, alpha-strike, homing flak.
    private static final Set<String> DEATH = Set.of(
        "lancer", "arc", "meltdown",            // beam
        "cyclone",                              // homing flak
        "foreshadow",                           // one-shot rail
        "spectre",                              // alpha-strike
        "scathe", "ripple",                     // artillery splash
        "fuse",                                 // shotgun, нечего ловить
        "tsunami",                              // slag status, разъедает
        "smite", "malign", "disperse",          // erekir beams / splash
        "afflict", "lustre",                    // erekir
        "breach", "diffuse"                     // erekir lasers
    );

    public static Kind classify(Block b) {
        if (b == null) return Kind.IGNORE;
        String n = b.name;
        if (BAIT.contains(n))  return Kind.BAITABLE;
        if (DEATH.contains(n)) return Kind.DEATH;
        return Kind.IGNORE;
    }
}
