// Build: 10
package dodger;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * 3-step beam search uклонение.
 *
 * Step 1: 360 кандидатов первого шага → топ-12 по одношаговому score.
 * Step 2: для каждого из 12 → 360 продолжений → топ-12 по двухшаговой сумме.
 * Step 3: для каждого из 12 → 360 продолжений → берём общий минимум трёхшагового score.
 *
 * Выбранное действие: первый шаг родословной победителя.
 * Trace: bestStep3 → beam2[parent2].ancestor1 → beam1[ancestor1].dx,dy
 *
 * Стоимость: 360 + 12*360 + 12*360 = 9000 траекторий × 25 тиков × ~30 пуль
 *            ≈ 6.75M bullet-checks/тик ≈ 405M/сек. ~3-5% одного ядра.
 *
 * Прочее (сохранилось из build 9): physical sim, lifetime, hysteresis,
 * damage weight, pivot bias.
 */
public final class BulletDodger {

    private static final float REACT_HORIZON   = 25f;
    private static final int   H               = 25;
    private static final float SAFE_R          = 12f;
    private static final float SCAN_R          = 200f;

    private static final int   SAMPLES         = 360;
    private static final int   BEAM_WIDTH      = 12;

    private static final float PIVOT_BIAS      = 0.01f;
    private static final float HYST_WEIGHT     = 0.5f;

    public int   threatCount;
    public float bestDanger;
    public int   bulletsScanned;
    public int   enemyBullets;

    private final Seq<Bullet> nearby = new Seq<>(64);
    private final Vec2  evade  = new Vec2();
    private final Vec2  retVel = new Vec2();

    private final float[] simX = new float[H + 1];
    private final float[] simY = new float[H + 1];

    /** Beam1 — топ-K первого шага. */
    private final BeamEntry[] beam1 = new BeamEntry[BEAM_WIDTH];
    /** Beam2 — топ-K двухшаговых планов. */
    private final BeamEntry[] beam2 = new BeamEntry[BEAM_WIDTH];
    /** Beam3 — топ-K трёхшаговых планов. */
    private final BeamEntry[] beam3 = new BeamEntry[BEAM_WIDTH];

    private float prevDx = 0f, prevDy = 0f;

    public BulletDodger() {
        for (int i = 0; i < BEAM_WIDTH; i++) {
            beam1[i] = new BeamEntry();
            beam2[i] = new BeamEntry();
            beam3[i] = new BeamEntry();
        }
    }

    /** Состояние одного узла beam search. */
    private static final class BeamEntry {
        float score;
        float dx, dy;          // первый шаг ЭТОГО узла (не корневой!)
        float endX, endY;      // позиция в конце шага
        float endVx, endVy;    // скорость в конце шага
        int   ancestor;        // индекс в предыдущем beam (или -1 для beam1)
    }

    private static void resetBeam(BeamEntry[] beam) {
        for (BeamEntry e : beam) e.score = Float.POSITIVE_INFINITY;
    }

    /** Вставка узла в beam (топ-K минимумов). */
    private static void insertBeam(BeamEntry[] beam,
                                   float score, float dx, float dy,
                                   float endX, float endY, float endVx, float endVy,
                                   int ancestor) {
        if (score >= beam[BEAM_WIDTH - 1].score) return;
        int pos = BEAM_WIDTH;
        for (int i = 0; i < BEAM_WIDTH; i++) {
            if (score < beam[i].score) { pos = i; break; }
        }
        if (pos >= BEAM_WIDTH) return;
        // ротация хвоста: возьмём последний элемент и вставим на pos
        BeamEntry tail = beam[BEAM_WIDTH - 1];
        for (int i = BEAM_WIDTH - 1; i > pos; i--) beam[i] = beam[i-1];
        beam[pos] = tail;
        tail.score = score;
        tail.dx = dx; tail.dy = dy;
        tail.endX = endX; tail.endY = endY;
        tail.endVx = endVx; tail.endVy = endVy;
        tail.ancestor = ancestor;
    }

    public Vec2 compute(Unit unit, Vec2 pivot) {
        evade.setZero();
        threatCount = 0;
        bestDanger = 0;
        bulletsScanned = 0;
        enemyBullets = 0;

        if (unit == null || unit.dead) return evade;

        final int   teamId = unit.team.id;
        final float speed = unit.type.speed;
        final float accel = unit.type.accel;
        final float drag  = unit.type.drag;
        final float ux = unit.x, uy = unit.y;
        final float uvx = unit.vel.x, uvy = unit.vel.y;

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
        Vec2 endVel = simulate(ux, uy, uvx, uvy, 0f, 0f, speed, accel, drag);
        float dangerStill = scoreSim(0f);
        if (dangerStill <= 1e-3f) return evade;

        float currentBias = pivotBias(ux, uy, pivot);

        // ============== STEP 1: 360 кандидатов → beam1[12] ==============
        resetBeam(beam1);
        // включаем "стоять на месте" как кандидата beam1[?] на случай если все направления хуже
        insertBeam(beam1, dangerStill + currentBias, 0f, 0f, ux, uy, uvx*(1-drag), uvy*(1-drag), -1);

        for (int k = 0; k < SAMPLES; k++) {
            float a  = k * 2f * Mathf.PI / SAMPLES;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            endVel = simulate(ux, uy, uvx, uvy, dx, dy, speed, accel, drag);
            float danger = scoreSim(0f);
            float fx = simX[H], fy = simY[H];
            float bias = pivotBias(fx, fy, pivot);
            float cont = -((dx*prevDx + dy*prevDy) / (speed*speed)) * HYST_WEIGHT;
            insertBeam(beam1, danger + bias + cont, dx, dy, fx, fy, endVel.x, endVel.y, -1);
        }

        // ============== STEP 2: для каждого из beam1 → 360 → beam2[12] ==============
        resetBeam(beam2);
        for (int p = 0; p < BEAM_WIDTH; p++) {
            BeamEntry parent = beam1[p];
            if (!Float.isFinite(parent.score)) continue;
            // baseline продолжения: стоять
            endVel = simulate(parent.endX, parent.endY, parent.endVx, parent.endVy,
                              0f, 0f, speed, accel, drag);
            float baseDanger = scoreSim(H);
            float baseBias = pivotBias(simX[H], simY[H], pivot);
            insertBeam(beam2, parent.score + baseDanger + baseBias, 0f, 0f,
                       simX[H], simY[H], endVel.x, endVel.y, p);

            for (int k = 0; k < SAMPLES; k++) {
                float a = k * 2f * Mathf.PI / SAMPLES;
                float dx = Mathf.cos(a) * speed;
                float dy = Mathf.sin(a) * speed;
                endVel = simulate(parent.endX, parent.endY, parent.endVx, parent.endVy,
                                  dx, dy, speed, accel, drag);
                float danger = scoreSim(H);
                float bias = pivotBias(simX[H], simY[H], pivot);
                float cont = -((dx*parent.dx + dy*parent.dy) / (speed*speed)) * HYST_WEIGHT * 0.3f;
                insertBeam(beam2, parent.score + danger + bias + cont, dx, dy,
                           simX[H], simY[H], endVel.x, endVel.y, p);
            }
        }

        // ============== STEP 3: для каждого beam2 → 360 → beam3[12] ==============
        resetBeam(beam3);
        for (int p = 0; p < BEAM_WIDTH; p++) {
            BeamEntry parent = beam2[p];
            if (!Float.isFinite(parent.score)) continue;
            // baseline
            endVel = simulate(parent.endX, parent.endY, parent.endVx, parent.endVy,
                              0f, 0f, speed, accel, drag);
            float baseDanger = scoreSim(2*H);
            float baseBias = pivotBias(simX[H], simY[H], pivot);
            insertBeam(beam3, parent.score + baseDanger + baseBias, 0f, 0f,
                       simX[H], simY[H], endVel.x, endVel.y, p);

            for (int k = 0; k < SAMPLES; k++) {
                float a = k * 2f * Mathf.PI / SAMPLES;
                float dx = Mathf.cos(a) * speed;
                float dy = Mathf.sin(a) * speed;
                endVel = simulate(parent.endX, parent.endY, parent.endVx, parent.endVy,
                                  dx, dy, speed, accel, drag);
                float danger = scoreSim(2*H);
                float bias = pivotBias(simX[H], simY[H], pivot);
                float cont = -((dx*parent.dx + dy*parent.dy) / (speed*speed)) * HYST_WEIGHT * 0.3f;
                insertBeam(beam3, parent.score + danger + bias + cont, dx, dy,
                           simX[H], simY[H], endVel.x, endVel.y, p);
            }
        }

        // ============== TRACE: победитель beam3 → beam2 → beam1 → action ==============
        BeamEntry winner3 = beam3[0];
        if (!Float.isFinite(winner3.score)) {
            prevDx = 0; prevDy = 0;
            return evade;
        }
        BeamEntry winner2 = beam2[winner3.ancestor];
        BeamEntry winner1 = beam1[winner2.ancestor];

        bestDanger = winner3.score;

        // если действие первого шага — стоять на месте, не дёргаемся
        if (winner1.dx == 0f && winner1.dy == 0f) {
            prevDx = 0; prevDy = 0;
            return evade;
        }

        // пересимулируем выбранный первый шаг для подсчёта реальных hits
        simulate(ux, uy, uvx, uvy, winner1.dx, winner1.dy, speed, accel, drag);
        threatCount = countSimHits(0f);

        prevDx = winner1.dx;
        prevDy = winner1.dy;
        evade.set(winner1.dx, winner1.dy);
        return evade;
    }

    /**
     * Симулирует H тиков физики юнита. Заполняет simX/simY[0..H], возвращает финальную скорость.
     */
    private Vec2 simulate(float startX, float startY, float startVx, float startVy,
                          float dx, float dy, float speed, float accel, float drag) {
        float len = Mathf.sqrt(dx*dx + dy*dy);
        float ndx = (len > 1e-4f) ? dx / len : 0f;
        float ndy = (len > 1e-4f) ? dy / len : 0f;
        float incrx = ndx * speed * accel;
        float incry = ndy * speed * accel;
        float damp  = 1f - drag;

        float vx = startVx, vy = startVy;
        float px = startX,  py = startY;
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
        retVel.set(vx, vy);
        return retVel;
    }

    private float scoreSim(float bulletTimeOffset) {
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

    private int countSimHits(float bulletTimeOffset) {
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
