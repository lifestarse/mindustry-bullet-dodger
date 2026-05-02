// Build: 16
package dodger;

import mindustry.type.UnitType;

import java.util.Set;

/**
 * Враждебные юниты, чьи атаки не уворачиваются (лучи/мгновенки/альфа-страйки).
 * Их зона действия должна быть death-zone — drone не должен заходить.
 */
public final class UnitCatalog {

    /** Юниты с не-доджибельным оружием (лазеры/постоянные лучи/мгновенный пирс). */
    private static final Set<String> NO_DODGE = Set.of(
        // Serpulo — летающие T4-T5
        "vela",          // continuous beam
        "quasar",        // shield + laser
        // Erekir
        "smite",         // pierce mega-beam
        // тяжёлые альфа-страйки T4-T5
        "antumbra",      // supercluster missiles
        "eradicator",    // heavy alpha
        "reign",         // ground T5 cannon
        "corvus",        // mega-laser
        "toxopid"        // toxin spores + spike
    );

    public static boolean isNoDodge(UnitType t) {
        return t != null && NO_DODGE.contains(t.name);
    }
}
