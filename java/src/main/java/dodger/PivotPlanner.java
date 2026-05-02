// Build: 12
package dodger;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.world.blocks.defense.turrets.Turret;

/**
 * Поиск pivot с aggressive scoring.
 *
 * Score(p) — float:
 *   для каждой baitable t, чей safe annulus накрывает p:
 *     contribution = 1 + closeness, где closeness ∈ [0..1]
 *       1 = на minDist (максимально близко безопасно к стволу)
 *       0 = на (range - ORBIT_R) (далеко, край радиуса)
 *   итого: каждая турель даёт 1..2, мотивируя залетать ближе.
 *
 * Кандидаты:
 *   (a) "inner ring": 8 точек на (minDist + 6) от каждой турели — агрессивно
 *   (b) "mid ring":   8 точек на середине annulus
 *   (c) попарные пересечения safe-disk границ
 *
 * Tiebreaker: при равном score берём ближе к юниту.
 */
public final class PivotPlanner {

    public static final float ORBIT_R              = 24f;
    public static final float SCAN_R               = 600f;
    public static final float HYSTERESIS           = 1.15f;
    public static final float INNER_OFFSET         = 6f;
    public static final int   DEFAULT_MIN_DIST     = 50;
    /** Текущая мин. дистанция pivot↔turret. Читается из Settings ("dodger.minDist"). */
    private float minDist = DEFAULT_MIN_DIST;

    /** Сверху сколько турелей считаем "близко" — больше => плотный огонь. */
    private static final float DENSITY_RADIUS = 80f;
    /** При plotности больше этого штрафуем score линейно вниз. */
    private static final int   DENSITY_SOFT_CAP = 3;

    private final Seq<Turret.TurretBuild> bait  = new Seq<>();
    private final Seq<Turret.TurretBuild> death = new Seq<>();

    public Vec2  currentPivot;
    public float currentScore = 0f;

    /** Текущая настройка density cap (читается на replan). */
    private boolean fDensityCap = true;

    public boolean replan(float ux, float uy, Team playerTeam) {
        // обновляем настройки
        minDist = Math.max(20f, Math.min(120f, Core.settings.getInt("dodger.minDist", DEFAULT_MIN_DIST)));
        fDensityCap = Core.settings.getBool("dodger.densityCap", true);
        bait.clear();
        death.clear();

        for (Teams.TeamData td : Vars.state.teams.getActive()) {
            if (td.team == playerTeam) continue;
            Vars.indexer.eachBlock(td.team, ux, uy, SCAN_R, b -> true, b -> {
                if (!(b instanceof Turret.TurretBuild tb)) return;
                TurretCatalog.Kind k = TurretCatalog.classify(b.block);
                if (k == TurretCatalog.Kind.BAITABLE)   bait.add(tb);
                else if (k == TurretCatalog.Kind.DEATH) death.add(tb);
            });
        }

        if (bait.isEmpty()) {
            currentPivot = null;
            currentScore = 0f;
            return true;
        }

        Vec2  best      = null;
        float bestScore = 0f;
        float bestDist2 = Float.POSITIVE_INFINITY;

        // (a) inner ring — агрессивные точки рядом со стволом
        for (Turret.TurretBuild t : bait) {
            float rMax = ((Turret) t.block).range - ORBIT_R;
            if (rMax <= minDist) continue;
            float r = minDist + INNER_OFFSET;
            for (int k = 0; k < 8; k++) {
                float a = k * Mathf.PI / 4f;
                float px = t.x + Mathf.cos(a) * r;
                float py = t.y + Mathf.sin(a) * r;
                float[] cand = { bestScore, bestDist2 };
                Vec2 v = pickIfBetter(px, py, ux, uy, cand, best);
                if (v != null) { best = v; bestScore = cand[0]; bestDist2 = cand[1]; }
            }
        }

        // (b) mid ring — компромиссные точки
        for (Turret.TurretBuild t : bait) {
            float rMax = ((Turret) t.block).range - ORBIT_R;
            if (rMax <= minDist) continue;
            float r = (minDist + rMax) * 0.5f;
            for (int k = 0; k < 8; k++) {
                float a = k * Mathf.PI / 4f;
                float px = t.x + Mathf.cos(a) * r;
                float py = t.y + Mathf.sin(a) * r;
                float[] cand = { bestScore, bestDist2 };
                Vec2 v = pickIfBetter(px, py, ux, uy, cand, best);
                if (v != null) { best = v; bestScore = cand[0]; bestDist2 = cand[1]; }
            }
        }

        // (c) попарные пересечения safe-disk границ
        for (int i = 0; i < bait.size; i++) {
            Turret.TurretBuild a = bait.get(i);
            float ra = ((Turret) a.block).range - ORBIT_R;
            if (ra <= 0) continue;
            for (int j = i + 1; j < bait.size; j++) {
                Turret.TurretBuild b = bait.get(j);
                float rb = ((Turret) b.block).range - ORBIT_R;
                if (rb <= 0) continue;
                Vec2[] xs = circleIntersections(a.x, a.y, ra, b.x, b.y, rb);
                if (xs == null) continue;
                for (Vec2 p : xs) {
                    float[] cand = { bestScore, bestDist2 };
                    Vec2 v = pickIfBetter(p.x, p.y, ux, uy, cand, best);
                    if (v != null) { best = v; bestScore = cand[0]; bestDist2 = cand[1]; }
                }
            }
        }

        if (best == null) {
            currentPivot = null;
            currentScore = 0f;
            return true;
        }

        if (currentPivot == null
            || bestScore > currentScore * HYSTERESIS
            || scoreAt(currentPivot.x, currentPivot.y) <= 0) {
            currentPivot = best;
            currentScore = bestScore;
            return true;
        }
        return false;
    }

    /**
     * Если точка (px,py) лучше текущего лучшего (по score, при равенстве — по distance до юнита),
     * возвращает новую Vec2 и обновляет cand[0]=newScore, cand[1]=newDist2.
     */
    private Vec2 pickIfBetter(float px, float py, float ux, float uy, float[] cand, Vec2 currentBest) {
        float s = scoreAt(px, py);
        if (s <= 0) return null;
        float d2 = (px - ux)*(px - ux) + (py - uy)*(py - uy);
        if (s > cand[0] || (Mathf.equal(s, cand[0]) && d2 < cand[1])) {
            cand[0] = s;
            cand[1] = d2;
            return new Vec2(px, py);
        }
        return null;
    }

    private float scoreAt(float x, float y) {
        for (Turret.TurretBuild d : death) {
            float r = ((Turret) d.block).range + ORBIT_R;
            float dx = x - d.x, dy = y - d.y;
            if (dx*dx + dy*dy <= r*r) return -1f;
        }
        float total = 0f;
        int   density = 0;          // сколько turrets в DENSITY_RADIUS — для штрафа
        for (Turret.TurretBuild t : bait) {
            float dx = x - t.x, dy = y - t.y;
            float d2 = dx*dx + dy*dy;
            float rMax = ((Turret) t.block).range - ORBIT_R;
            if (d2 > rMax*rMax) continue;
            if (d2 < minDist*minDist) return -1f;
            float d = Mathf.sqrt(d2);
            float closeness = 1f - (d - minDist) / (rMax - minDist);
            total += 1f + closeness;
            if (d2 < DENSITY_RADIUS*DENSITY_RADIUS) density++;
        }
        // штраф за слишком плотный огонь: больше DENSITY_SOFT_CAP "близких" — линейный спад
        if (fDensityCap && density > DENSITY_SOFT_CAP) {
            float factor = Math.max(0f, 1f - (density - DENSITY_SOFT_CAP) * 0.3f);
            total *= factor;
        }
        return total;
    }

    private static Vec2[] circleIntersections(float x1, float y1, float r1,
                                              float x2, float y2, float r2) {
        float dx = x2 - x1, dy = y2 - y1;
        float d2 = dx*dx + dy*dy;
        float d  = Mathf.sqrt(d2);
        if (d > r1 + r2 || d < Math.abs(r1 - r2) || d == 0) return null;
        float a = (r1*r1 - r2*r2 + d2) / (2 * d);
        float h2 = r1*r1 - a*a;
        if (h2 < 0) return null;
        float h = Mathf.sqrt(h2);
        float mx = x1 + a * dx / d;
        float my = y1 + a * dy / d;
        float rx = -dy * h / d;
        float ry =  dx * h / d;
        return new Vec2[] { new Vec2(mx + rx, my + ry), new Vec2(mx - rx, my - ry) };
    }
}
