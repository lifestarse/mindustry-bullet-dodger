// Build: 6
package dodger;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * Sampling-based реактивное уклонение.
 *
 * Каждый тик:
 *   1. Собираем все вражеские пули в SCAN_R.
 *   2. Сэмплируем N направлений (по кругу).
 *   3. Для каждого направления считаем "опасность" = взвешенную сумму
 *      пуль, которые попали бы в юнита, если бы он двигался в эту сторону.
 *   4. Возвращаем направление с минимумом опасности (масштабированное до speed).
 *
 * Опасность одной пули в кандидатной траектории юнита (vel = (dx,dy)):
 *   t* = решение CPA для двух движущихся объектов
 *   если t* в [0, REACT_HORIZON] И dCPA < SAFE_R: contribute (SAFE_R-dCPA)*(REACT_HORIZON-t)/REACT_HORIZON
 *
 * Если ни в одном из 16 направлений опасности нет — возвращаем zero (пусть GOTO работает).
 */
public final class BulletDodger {

    private static final float REACT_HORIZON  = 25f;
    private static final float SAFE_R         = 12f;
    private static final float SCAN_R         = 200f;
    private static final int   SAMPLES        = 720;
    /** Бонус за близость к pivot после уклонения. ~ 1 ед. опасности ≈ 100 px дрейфа. */
    private static final float PIVOT_BIAS     = 0.01f;

    public int   threatCount;
    public float bestDanger;
    public int   bulletsScanned;
    public int   enemyBullets;

    private final Seq<Bullet> nearby = new Seq<>(64);
    private final Vec2 evade = new Vec2();

    public Vec2 compute(Unit unit, Vec2 pivot) {
        evade.setZero();
        threatCount = 0;
        bestDanger = 0;
        bulletsScanned = 0;
        enemyBullets = 0;

        if (unit == null || unit.dead) return evade;

        final float ux = unit.x, uy = unit.y;
        final int   teamId = unit.team.id;
        final float speed = unit.type.speed;

        nearby.clear();
        Groups.bullet.each(b -> {
            if (b == null || !b.isAdded() || b.type == null) return;
            float dx = b.x - ux, dy = b.y - uy;
            float d2 = dx*dx + dy*dy;
            if (d2 > SCAN_R*SCAN_R) return;
            bulletsScanned++;
            if (b.team.id == teamId) return;
            enemyBullets++;
            nearby.add(b);
        });

        if (nearby.isEmpty()) return evade;

        // Сначала проверим: есть ли вообще угроза если стоять на месте?
        float dangerStill = scoreDir(unit, 0f, 0f);
        if (dangerStill <= 1e-3f) return evade; // ничего не угрожает — пусть GOTO работает

        // baseline: стоять на месте + bias от текущей дистанции до pivot
        float currentBias = pivotBias(unit.x, unit.y, pivot);
        float bestScore = dangerStill + currentBias;
        float bestDx = 0, bestDy = 0;
        boolean foundBetter = false;

        for (int k = 0; k < SAMPLES; k++) {
            float a  = k * 2f * Mathf.PI / SAMPLES;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            float danger = scoreDir(unit, dx, dy);
            float fx = unit.x + dx * REACT_HORIZON;
            float fy = unit.y + dy * REACT_HORIZON;
            float bias = pivotBias(fx, fy, pivot);
            float s = danger + bias;
            if (s < bestScore) {
                bestScore = s;
                bestDx = dx;
                bestDy = dy;
                foundBetter = true;
            }
        }

        bestDanger = bestScore;

        if (!foundBetter) {
            // даже стояние не хуже чем все направления — стоим на месте,
            // GOTO будет тянуть к pivot, лучше чем суицидальный уход
            return evade;
        }

        // Подсчитаем сколько пуль реально пролетит близко в выбранном направлении
        threatCount = countHits(unit, bestDx, bestDy);
        evade.set(bestDx, bestDy);
        return evade;
    }

    private float pivotBias(float fx, float fy, Vec2 pivot) {
        if (pivot == null) return 0f;
        float dx = fx - pivot.x, dy = fy - pivot.y;
        return Mathf.sqrt(dx*dx + dy*dy) * PIVOT_BIAS;
    }

    private float scoreDir(Unit unit, float dx, float dy) {
        float total = 0;
        float ux = unit.x, uy = unit.y;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            float A = b.x - ux;
            float B = b.y - uy;
            float C = b.vel.x - dx;
            float D = b.vel.y - dy;
            float vv = C*C + D*D;
            if (vv < 1e-4f) continue;
            float t = -(A*C + B*D) / vv;
            if (t < 0 || t > REACT_HORIZON) continue;
            float ex = A + C*t;
            float ey = B + D*t;
            float dCPA = Mathf.sqrt(ex*ex + ey*ey);
            if (dCPA >= SAFE_R) continue;
            // weight: ближе и скорее = больше штраф
            total += (SAFE_R - dCPA) * (REACT_HORIZON - t) / REACT_HORIZON;
        }
        return total;
    }

    private int countHits(Unit unit, float dx, float dy) {
        int n = 0;
        float ux = unit.x, uy = unit.y;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            float A = b.x - ux;
            float B = b.y - uy;
            float C = b.vel.x - dx;
            float D = b.vel.y - dy;
            float vv = C*C + D*D;
            if (vv < 1e-4f) continue;
            float t = -(A*C + B*D) / vv;
            if (t < 0 || t > REACT_HORIZON) continue;
            float ex = A + C*t;
            float ey = B + D*t;
            float dCPA = Mathf.sqrt(ex*ex + ey*ey);
            if (dCPA < SAFE_R) n++;
        }
        return n;
    }
}
