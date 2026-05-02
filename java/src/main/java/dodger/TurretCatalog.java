// Build: 17
package dodger;

import arc.Core;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.List;
import java.util.Set;

/**
 * Классификация турелей.
 *
 * Динамические правила:
 *   1. turret.targetAir == false  → IGNORE (мы летаем, ground-only похуй).
 *   2. По имени блока ищем в списках:
 *      BAIT_DEFAULT (5 шт.) — баитим по умолчанию
 *      BAIT_OPTIONAL (9 шт.) — опт-ин через Settings
 *   3. Если включено в Settings (`dodger.bait.<name>`), классификация = BAITABLE.
 *   4. Иначе если в DEATH list — DEATH.
 *   5. Иначе IGNORE.
 *
 * Это позволяет:
 *   - Включать рискованные турели (Cyclone, Spectre, Foreshadow) как цели для байта.
 *   - Выключать любую default турель (например, в зоне Hail+Lancer она бесполезна).
 */
public final class TurretCatalog {

    public enum Kind { BAITABLE, DEATH, IGNORE }

    /** Default-on: баитятся при первом запуске. Дёшево, безопасно. */
    public static final List<String> BAIT_DEFAULT = List.of(
        "duo", "salvo", "hail", "scorch", "wave"
    );

    /** Опт-ин: turreты с пулями, но рискованные (splash/homing/alpha). */
    public static final List<String> BAIT_OPTIONAL = List.of(
        "scatter", "swarmer", "cyclone", "spectre", "ripple", "scathe",
        "foreshadow", "fuse", "tsunami"
    );

    /** Турели, которые НЕВОЗМОЖНО доджить (beam/мгновенки). Всегда DEATH. */
    private static final Set<String> DEATH_HARD = Set.of(
        "lancer", "arc", "meltdown",            // beam
        "smite", "malign", "disperse",          // erekir beams / splash
        "afflict", "lustre",                    // erekir
        "breach", "diffuse"                     // erekir lasers
    );

    public static Kind classify(Block b) {
        if (b == null) return Kind.IGNORE;
        // Ground-only — игнор
        if (b instanceof Turret turret && !turret.targetAir) return Kind.IGNORE;

        String n = b.name;
        boolean defOn = BAIT_DEFAULT.contains(n);
        boolean optional = BAIT_OPTIONAL.contains(n);

        if (defOn || optional) {
            if (Core.settings.getBool("dodger.bait." + n, defOn)) return Kind.BAITABLE;
        }

        // Hard-death (beam/instant) — всегда DEATH, нельзя байтить.
        if (DEATH_HARD.contains(n)) return Kind.DEATH;

        // Optional, но не выбран в bait → DEATH (если был в опт-ин = с пулями но рискован)
        if (optional) return Kind.DEATH;

        return Kind.IGNORE;
    }
}
