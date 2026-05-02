// Build: 9
package dodger;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * Sampling-based уклонение с 2-step beam search и физической симуляцией юнита.
 *
 * Было (build 8): на каждом тике из 720 направлений выбираем одно с минимальным
 * danger на горизонте H=25. Жадно. Если безопасный шаг сейчас приводит в
 * безвыходную позицию через H тиков — single-step этого не видит.
 *
 * Стало (build 9): двухшаговое планирование (beam search):
 *   1. Сэмплируем SAMPLES_FIRST=720 первых шагов, симулируем каждое над [0..H].
 *   2. Берём топ-BEAM=6 по сумме (danger + bias + cont).
 *   3. Для каждого из BEAM в стартовой точке (pos, vel) после H тиков
 *      сэмплируем SAMPLES_SECOND=180 продолжений, симулируем над [H..2H].
 *   4. score_2step(k) = score_1[k] + min_over_secondary(score_2[k][j]).
 *   5. Возвращаем первый шаг с минимальным двухшаговым score'ом.
 *
 * Стоимость: 720 + 6*180 = 1800 траекторий × 25 тиков × ~30 пуль ≈ 1.35M
 * операций/тик ≈ 80M ops/s — пыль для современного CPU.
 *
 * Доп.фичи (из build 8): инерция, lifetime, hysteresis, damage weight.
 */
public final class BulletDodger {

    private static final float REACT_HORIZON   = 25f;
    private static final int   H               = 25;
    private static final float SAFE_R          = 12f;
    private static final float SCAN_R          = 200f;
    private static final int   SAMPLES_FIRST   = 720;
    private static final int   SAMPLES_SECOND  = 180;
    private static final int   BEAM_WIDTH      = 6;
    private static final float PIVOT_BIAS      = 0.01f;
    private static final float HYST_WEIGHT     = 0.5f;

    public int   threatCount;
    public float bestDanger;
    public int   bulletsScanned;
    public int   enemyBullets;

    private final Seq<Bullet> nearby = new Seq<>(64);
    private final Vec2  evade = new Vec2();

    // первый шаг
    private final float[] simX = new float[H + 1];
    private final float[] simY = new float[H + 1];
    // второй шаг
    private final float[] simX2 = new float[H + 1];
    private final float[] simY2 = new float[H + 1];

    // beam: топ-K первых шагов
    private final float[] beamScore = new float[BEAM_WIDTH];
    private final float[] beamDx    = new float[BEAM_WIDTH];
    private final float[] beamDy    = new float[BEAM_WIDTH];
    private final float[] beamEndX  = new float[BEAM_WIDTH];
    private final float[] beamEndY  = new float[BEAM_WIDTH];
    private final float[] beamEndVx = new float[BEAM_WIDTH];
    private final float[] beamEndVy = new float[BEAM_WIDTH];

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

        // baseline: стоять на месте
        Vec2 endVel = simulate(unit.x, unit.y, unit.vel.x, unit.vel.y, 0f, 0f,
                                speed, accel, drag, simX, simY);
        float dangerStill = scoreSim(simX, simY, 0f);
        if (dangerStill <= 1e-3f) return evade;

        float currentBias = pivotBias(unit.x, unit.y, pivot);

        // ---- ПЕРВЫЙ ПРОХОД: 720 направлений, ведём топ-BEAM ----
        for (int i = 0; i < BEAM_WIDTH; i++) beamScore[i] = Float.POSITIVE_INFINITY;

        // в beam[0] вкладываем "стоять на месте" baseline
        insertBeam(dangerStill + currentBias, 0f, 0f,
                   unit.x, unit.y, unit.vel.x*(1-drag), unit.vel.y*(1-drag));
        // (грубая оценка финальной скорости при простое — неважно для дальнейшего deep)

        for (int k = 0; k < SAMPLES_FIRST; k++) {
            float a  = k * 2f * Mathf.PI / SAMPLES_FIRST;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            endVel = simulate(unit.x, unit.y, unit.vel.x, unit.vel.y, dx, dy,
                              speed, accel, drag, simX, simY);
            float danger = scoreSim(simX, simY, 0f);
            float fx = simX[H], fy = simY[H];
            float bias = pivotBias(fx, fy, pivot);
            float cont = -((dx*prevDx + dy*prevDy) / (speed*speed)) * HYST_WEIGHT;
            float s = danger + bias + cont;

            if (s < beamScore[BEAM_WIDTH - 1]) {
                insertBeam(s, dx, dy, fx, fy, endVel.x, endVel.y);
            }
        }

        // ---- ВТОРОЙ ПРОХОД: для каждого из BEAM_WIDTH делаем deep search ----
        float bestCombined = Float.POSITIVE_INFINITY;
        int   bestIdx = -1;

        for (int i = 0; i < BEAM_WIDTH; i++) {
            if (!Float.isFinite(beamScore[i])) continue;

            float minSecond = scoreSecondStep(
                beamEndX[i], beamEndY[i], beamEndVx[i], beamEndVy[i],
                speed, accel, drag, pivot, beamDx[i], beamDy[i]);

            float combined = beamScore[i] + minSecond;
            if (combined < bestCombined) {
                bestCombined = combined;
                bestIdx = i;
            }
        }

        bestDanger = bestCombined;

        // если "стоять на месте" (beam[0] dx=dy=0) победило по сумме — не дёргаемся
        if (bestIdx < 0 || (beamDx[bestIdx] == 0f && beamDy[bestIdx] == 0f)) {
            prevDx = 0; prevDy = 0;
            return evade;
        }

        // пересимулируем выбранный первый шаг для подсчёта реальных hits
        simulate(unit.x, unit.y, unit.vel.x, unit.vel.y,
                 beamDx[bestIdx], beamDy[bestIdx],
                 speed, accel, drag, simX, simY);
        threatCount = countSimHits(simX, simY, 0f);

        prevDx = beamDx[bestIdx];
        prevDy = beamDy[bestIdx];
        evade.set(beamDx[bestIdx], beamDy[bestIdx]);
        return evade;
    }

    /**
     * Из (startPos, startVel) после H тиков ищем лучший второй шаг (минимум second-step danger).
     * prevDxFirst/prevDyFirst — направление первого шага (для continuity на стыке).
     */
    private float scoreSecondStep(float startX, float startY, float startVx, float startVy,
                                  float speed, float accel, float drag,
                                  Vec2 pivot, float prevDxFirst, float prevDyFirst) {
        float minScore = Float.POSITIVE_INFINITY;

        // baseline: продолжать инерцию (без новой команды)
        Vec2 endVel = simulate(startX, startY, startVx, startVy, 0f, 0f,
                               speed, accel, drag, simX2, simY2);
        float baseScore = scoreSim(simX2, simY2, H);
        baseScore += pivotBias(simX2[H], simY2[H], pivot);
        if (baseScore < minScore) minScore = baseScore;

        for (int k = 0; k < SAMPLES_SECOND; k++) {
            float a  = k * 2f * Mathf.PI / SAMPLES_SECOND;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            simulate(startX, startY, startVx, startVy, dx, dy,
                     speed, accel, drag, simX2, simY2);
            float danger = scoreSim(simX2, simY2, H);
            float bias = pivotBias(simX2[H], simY2[H], pivot);
            // continuity между первым и вторым шагом — мелкий бонус
            float cont = -((dx*prevDxFirst + dy*prevDyFirst) / (speed*speed)) * HYST_WEIGHT * 0.3f;
            float s = danger + bias + cont;
            if (s < minScore) minScore = s;
        }

        return minScore;
    }

    /**
     * Вставка в отсортированный по возрастанию beam (топ-K минимумов).
     */
    private void insertBeam(float score, float dx, float dy,
                            float endX, float endY, float endVx, float endVy) {
        int pos = BEAM_WIDTH;
        for (int i = 0; i < BEAM_WIDTH; i++) {
            if (score < beamScore[i]) { pos = i; break; }
        }
        if (pos >= BEAM_WIDTH) return;
        // сдвигаем хвост вправо
        for (int i = BEAM_WIDTH - 1; i > pos; i--) {
            beamScore[i] = beamScore[i-1];
            beamDx[i]    = beamDx[i-1];
            beamDy[i]    = beamDy[i-1];
            beamEndX[i]  = beamEndX[i-1];
            beamEndY[i]  = beamEndY[i-1];
            beamEndVx[i] = beamEndVx[i-1];
            beamEndVy[i] = beamEndVy[i-1];
        }
        beamScore[pos] = score;
        beamDx[pos]    = dx;
        beamDy[pos]    = dy;
        beamEndX[pos]  = endX;
        beamEndY[pos]  = endY;
        beamEndVx[pos] = endVx;
        beamEndVy[pos] = endVy;
    }

    /**
     * Симулирует H тиков физики юнита из (startX,Y) и (startVx,Vy) с пожеланием (dx,dy).
     * Заполняет outX/outY[0..H], возвращает финальную скорость (через статический Vec2 выход).
     */
    private final Vec2 retVel = new Vec2();
    private Vec2 simulate(float startX, float startY, float startVx, float startVy,
                           float dx, float dy, float speed, float accel, float drag,
                           float[] outX, float[] outY) {
        float len = Mathf.sqrt(dx*dx + dy*dy);
        float ndx = (len > 1e-4f) ? dx / len : 0f;
        float ndy = (len > 1e-4f) ? dy / len : 0f;
        float incrx = ndx * speed * accel;
        float incry = ndy * speed * accel;
        float damp  = 1f - drag;

        float vx = startVx, vy = startVy;
        float px = startX,  py = startY;
        outX[0] = px; outY[0] = py;
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
            outX[t] = px;
            outY[t] = py;
        }
        retVel.set(vx, vy);
        return retVel;
    }

    /**
     * danger симулированной траектории outX/outY[0..H] относительно пуль,
     * сдвинутых во времени на bulletTimeOffset (0 для первого шага, H для второго).
     */
    private float scoreSim(float[] outX, float[] outY, float bulletTimeOffset) {
        float total = 0;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            float remain = b.type.lifetime - b.time - bulletTimeOffset;
            int   maxT = (int) Math.min(H, Math.max(0, remain));
            if (maxT <= 0) continue;

            float minD2 = Float.POSITIVE_INFINITY;
            int   minT  = 0;
            for (int t = 0; t <= maxT; t++) {
                float bt = bulletTimeOffset + t;
                float bx = b.x + b.vel.x * bt;
                float by = b.y + b.vel.y * bt;
                float dx = bx - outX[t];
                float dy = by - outY[t];
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

    private int countSimHits(float[] outX, float[] outY, float bulletTimeOffset) {
        int n = 0;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            float remain = b.type.lifetime - b.time - bulletTimeOffset;
            int   maxT = (int) Math.min(H, Math.max(0, remain));
            if (maxT <= 0) continue;
            float minD2 = Float.POSITIVE_INFINITY;
            for (int t = 0; t <= maxT; t++) {
                float bt = bulletTimeOffset + t;
                float bx = b.x + b.vel.x * bt;
                float by = b.y + b.vel.y * bt;
                float dx = bx - outX[t];
                float dy = by - outY[t];
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
