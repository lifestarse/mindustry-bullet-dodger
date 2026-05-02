// Build: 16
package dodger;

import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.Set;

/**
 * Классификация турелей.
 *
 * Динамические правила (важнее имени):
 *   - turret.targetAir == false  → IGNORE  (мы летаем, ground-only турели нам похуй)
 *
 * Иначе — по имени блока:
 *   - BAIT  : снаряды баллистические, можем уворачиваться → баитим патроны
 *   - DEATH : beam/instant/alpha-strike → строго избегаем, и pivot, и трактории
 */
public final class TurretCatalog {

    public enum Kind { BAITABLE, DEATH, IGNORE }

    private static final Set<String> BAIT = Set.of(
        "duo", "salvo", "hail", "scorch", "wave"
    );

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
        // Ground-only турели — игнор: drone летает, они в нас не стреляют
        if (b instanceof Turret turret && !turret.targetAir) return Kind.IGNORE;
        String n = b.name;
        if (BAIT.contains(n))  return Kind.BAITABLE;
        if (DEATH.contains(n)) return Kind.DEATH;
        return Kind.IGNORE;
    }
}
