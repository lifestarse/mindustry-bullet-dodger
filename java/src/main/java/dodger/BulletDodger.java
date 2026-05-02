// Build: 8
package dodger;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * Sampling-based уклонение с физической симуляцией юнита.
 *
 * Улучшения относительно build 6:
 *   #1 Инерция: симулируем accel/drag/limit юнита потактово, а не "мгновенная скорость".
 *   #3 Lifetime: пули, которые сами развеются раньше попадания, игнорим.
 *   #5 Hysteresis: бонус за продолжение прежнего направления (давит дребезг).
 *   #7 Damage weight: пуля 80 урона "опаснее" пули 9 урона, weight = b.damage/15 в [0.5..5].
 *
 * Алгоритм:
 *   Для каждого из SAMPLES направлений:
 *     1. Симулируем 26 тиков движения юнита (vel += norm * speed * accel; vel *= 1-drag; clip).
 *     2. Получаем массив позиций юнита simX[t], simY[t] для t=0..H.
 *     3. Для каждой пули считаем мин. расстояние до simX/simY на интервале [0, min(H, remaining_lifetime)].
 *     4. Если minDist < SAFE_R — contribute (SAFE_R-minDist)*(H-minT)/H * damageWeight.
 *   Плюс bias за дистанцию до pivot и continuity bonus.
 */
public final class BulletDodger {

    private static final float REACT_HORIZON  = 25f;
    private static final int   H              = 25;
    private static final float SAFE_R         = 12f;
    private static final float SCAN_R         = 200f;
    private static final int   SAMPLES        = 720;
    private static final float PIVOT_BIAS     = 0.01f;
    private static final float HYST_WEIGHT    = 0.5f;

    public int   threatCount;
    public float bestDanger;
    public int   bulletsScanned;
    public int   enemyBullets;

    private final Seq<Bullet> nearby = new Seq<>(64);
    private final Vec2  evade = new Vec2();
    private final float[] simX = new float[H + 1];
    private final float[] simY = new float[H + 1];

    /** Предыдущий выбранный вектор уклонения (для continuity-bonus). */
    private float prevDx = 0f, prevDy = 0f;

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
        final float accel = unit.type.accel;
        final float drag  = unit.type.drag;

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

        // baseline: симулируем "стоять на месте" (zero direction), считаем danger
        simulate(unit, 0f, 0f, speed, accel, drag);
        float dangerStill = scoreSim();
        if (dangerStill <= 1e-3f) return evade;

        float currentBias = pivotBias(unit.x, unit.y, pivot);
        float bestScore = dangerStill + currentBias;
        float bestDx = 0, bestDy = 0;
        boolean foundBetter = false;

        for (int k = 0; k < SAMPLES; k++) {
            float a  = k * 2f * Mathf.PI / SAMPLES;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            simulate(unit, dx, dy, speed, accel, drag);
            float danger = scoreSim();
            float fx = simX[H], fy = simY[H];
            float bias = pivotBias(fx, fy, pivot);
            // continuity: bonus если близко к предыдущему направлению (cos угла)
            // dx/speed * prevDx/speed = cos(angle) ∈ [-1..1]
            float cont = -((dx*prevDx + dy*prevDy) / (speed*speed)) * HYST_WEIGHT;
            float s = danger + bias + cont;
            if (s < bestScore) {
                bestScore = s;
                bestDx = dx;
                bestDy = dy;
                foundBetter = true;
            }
        }

        bestDanger = bestScore;

        if (!foundBetter) {
            // даже стояние не хуже всех направлений
            prevDx = 0; prevDy = 0;
            return evade;
        }

        // пересимулируем выбранное и посчитаем "сколько реально цепанёт"
        simulate(unit, bestDx, bestDy, speed, accel, drag);
        threatCount = countSimHits();

        prevDx = bestDx;
        prevDy = bestDy;
        evade.set(bestDx, bestDy);
        return evade;
    }

    /** Заполняет simX/simY: позиция юнита на каждом тике [0..H] при пожелании vel=(dx,dy). */
    private void simulate(Unit unit, float dx, float dy, float speed, float accel, float drag) {
        // нормализованное направление желаемой скорости
        float len = Mathf.sqrt(dx*dx + dy*dy);
        float ndx = (len > 1e-4f) ? dx / len : 0f;
        float ndy = (len > 1e-4f) ? dy / len : 0f;
        float incrx = ndx * speed * accel;
        float incry = ndy * speed * accel;
        float damp  = 1f - drag;

        float vx = unit.vel.x;
        float vy = unit.vel.y;
        float px = unit.x;
        float py = unit.y;
        simX[0] = px; simY[0] = py;
        for (int t = 1; t <= H; t++) {
            vx += incrx;
            vy += incry;
            vx *= damp;
            vy *= damp;
            float vlen2 = vx*vx + vy*vy;
            if (vlen2 > speed*speed) {
                float vlen = Mathf.sqrt(vlen2);
                vx *= speed / vlen;
                vy *= speed / vlen;
            }
            px += vx;
            py += vy;
            simX[t] = px;
            simY[t] = py;
        }
    }

    private float scoreSim() {
        float total = 0;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            // оставшееся время жизни пули (тиков)
            float remain = b.type.lifetime - b.time;
            int   maxT = (int) Math.min(H, Math.max(0, remain));
            if (maxT <= 0) continue;

            float minD2 = Float.POSITIVE_INFINITY;
            int   minT  = 0;
            for (int t = 0; t <= maxT; t++) {
                float bx = b.x + b.vel.x * t;
                float by = b.y + b.vel.y * t;
                float dx = bx - simX[t];
                float dy = by - simY[t];
                float d2 = dx*dx + dy*dy;
                if (d2 < minD2) { minD2 = d2; minT = t; }
            }
            if (minD2 >= SAFE_R*SAFE_R) continue;
            float dCPA = Mathf.sqrt(minD2);
            float wDam = Mathf.clamp(b.damage / 15f, 0.5f, 5f);
            total += (SAFE_R - dCPA) * (REACT_HORIZON - minT) / REACT_HORIZON * wDam;
        }
        return total;
    }

    private int countSimHits() {
        int n = 0;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            float remain = b.type.lifetime - b.time;
            int   maxT = (int) Math.min(H, Math.max(0, remain));
            if (maxT <= 0) continue;
            float minD2 = Float.POSITIVE_INFINITY;
            for (int t = 0; t <= maxT; t++) {
                float bx = b.x + b.vel.x * t;
                float by = b.y + b.vel.y * t;
                float dx = bx - simX[t];
                float dy = by - simY[t];
                float d2 = dx*dx + dy*dy;
                if (d2 < minD2) minD2 = d2;
            }
            if (minD2 < SAFE_R*SAFE_R) n++;
        }
        return n;
    }

    private float pivotBias(float fx, float fy, Vec2 pivot) {
        if (pivot == null) return 0f;
        float dx = fx - pivot.x, dy = fy - pivot.y;
        return Mathf.sqrt(dx*dx + dy*dy) * PIVOT_BIAS;
    }
}
