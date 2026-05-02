// Build: 13
package dodger;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * 1..3-step beam search uклонение. Параметры читаются из Core.settings:
 *   dodger.samples   — направлений-кандидатов на шаг (45/90/180/360/540/720)
 *   dodger.steps     — глубина planning'а (1/2/3)
 *   dodger.beamWidth — сколько лучших узлов держим между шагами (1..MAX_BEAM)
 *
 * Внутри: массивы под BEAM_MAX = 16, реально используется первые N.
 *
 * Step 1: SAMPLES первых ходов из (unit.x/y, unit.vel) → beam1[N]
 * Step 2: для каждого beam1 — SAMPLES продолжений → beam2[N]
 * Step 3: для каждого beam2 — SAMPLES продолжений → beam3[N]
 * Победитель — наименьший score; trace через ancestors к первому шагу.
 *
 * Каждая симуляция учитывает физику юнита: vel += norm * speed * accel; vel *= 1-drag; clip.
 * Каждая пуля проверяется на t* минимума dCPA в окне [0, min(H, remaining_lifetime)].
 * danger взвешивается по урону пули (Spectre 80 → ×5, Duo медь 9 → ×0.6).
 */
public final class BulletDodger {

    private static final float REACT_HORIZON = 25f;
    private static final int   H             = 25;
    private static final float SAFE_R        = 12f;
    private static final float SCAN_R        = 200f;
    private static final int   BEAM_MAX      = 16;
    private static final float PIVOT_BIAS    = 0.01f;
    private static final float HYST_WEIGHT   = 0.5f;

    /** Дефолты для settings, если ключи ещё не заданы. */
    public static final int    DEFAULT_SAMPLES    = 360;
    public static final int    DEFAULT_STEPS      = 3;
    public static final int    DEFAULT_BEAM_WIDTH = 12;

    public int   threatCount;
    public float bestDanger;
    public int   bulletsScanned;
    public int   enemyBullets;
    public int   lastSamples;
    public int   lastSteps;
    public int   lastBeam;

    // тумблеры фич (читаются один раз за compute, дефолт true = текущее поведение)
    private boolean fPhysics;
    private boolean fLifetime;
    private boolean fHysteresis;
    private boolean fDamageWeight;

    private final Seq<Bullet> nearby = new Seq<>(64);
    private final Vec2  evade  = new Vec2();
    private final Vec2  retVel = new Vec2();

    private final float[] simX = new float[H + 1];
    private final float[] simY = new float[H + 1];

    private final BeamEntry[] beam1 = new BeamEntry[BEAM_MAX];
    private final BeamEntry[] beam2 = new BeamEntry[BEAM_MAX];
    private final BeamEntry[] beam3 = new BeamEntry[BEAM_MAX];

    private float prevDx = 0f, prevDy = 0f;

    public BulletDodger() {
        for (int i = 0; i < BEAM_MAX; i++) {
            beam1[i] = new BeamEntry();
            beam2[i] = new BeamEntry();
            beam3[i] = new BeamEntry();
        }
    }

    private static final class BeamEntry {
        float score;
        float dx, dy;
        float endX, endY;
        float endVx, endVy;
        int   ancestor;
    }

    private static void resetBeam(BeamEntry[] beam, int width) {
        for (int i = 0; i < width; i++) beam[i].score = Float.POSITIVE_INFINITY;
    }

    private static void insertBeam(BeamEntry[] beam, int width,
                                   float score, float dx, float dy,
                                   float endX, float endY, float endVx, float endVy,
                                   int ancestor) {
        if (score >= beam[width - 1].score) return;
        int pos = width;
        for (int i = 0; i < width; i++) {
            if (score < beam[i].score) { pos = i; break; }
        }
        if (pos >= width) return;
        BeamEntry tail = beam[width - 1];
        for (int i = width - 1; i > pos; i--) beam[i] = beam[i-1];
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

        // ---- читаем настройки ----
        int samples = clampInt(Core.settings.getInt("dodger.samples", DEFAULT_SAMPLES), 8, 720);
        int steps   = clampInt(Core.settings.getInt("dodger.steps",   DEFAULT_STEPS),   1, 3);
        int beam    = clampInt(Core.settings.getInt("dodger.beamWidth", DEFAULT_BEAM_WIDTH), 1, BEAM_MAX);
        // physics OFF по умолчанию: мы пишем unit.vel.set, скорость меняется мгновенно.
        // Включать имеет смысл только если переключаешься на movePref-управление.
        fPhysics      = Core.settings.getBool("dodger.physics",      false);
        fLifetime     = Core.settings.getBool("dodger.lifetime",     true);
        fHysteresis   = Core.settings.getBool("dodger.hysteresis",   true);
        fDamageWeight = Core.settings.getBool("dodger.damageWeight", true);
        lastSamples = samples;
        lastSteps   = steps;
        lastBeam    = beam;

        final int   teamId = unit.team.id;
        final float speed  = unit.type.speed;
        final float accel  = unit.type.accel;
        final float drag   = unit.type.drag;
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

        // baseline
        Vec2 endVel = simulate(ux, uy, uvx, uvy, 0f, 0f, speed, accel, drag);
        float dangerStill = scoreSim(0f);
        if (dangerStill <= 1e-3f) return evade;

        float currentBias = pivotBias(ux, uy, pivot);

        // ---------- STEP 1 ----------
        resetBeam(beam1, beam);
        insertBeam(beam1, beam, dangerStill + currentBias, 0f, 0f,
                   ux, uy, uvx*(1-drag), uvy*(1-drag), -1);

        for (int k = 0; k < samples; k++) {
            float a  = k * 2f * Mathf.PI / samples;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            endVel = simulate(ux, uy, uvx, uvy, dx, dy, speed, accel, drag);
            float danger = scoreSim(0f);
            float fx = simX[H], fy = simY[H];
            float bias = pivotBias(fx, fy, pivot);
            float cont = fHysteresis ? -((dx*prevDx + dy*prevDy) / (speed*speed)) * HYST_WEIGHT : 0f;
            insertBeam(beam1, beam, danger + bias + cont, dx, dy, fx, fy, endVel.x, endVel.y, -1);
        }

        // если steps == 1, выбираем из beam1 и заканчиваем
        if (steps == 1) {
            return finish(beam1, beam, ux, uy, uvx, uvy, speed, accel, drag, null, null);
        }

        // ---------- STEP 2 ----------
        resetBeam(beam2, beam);
        for (int p = 0; p < beam; p++) {
            BeamEntry parent = beam1[p];
            if (!Float.isFinite(parent.score)) continue;
            expandBeam(parent, beam2, beam, samples, p, H, speed, accel, drag, pivot);
        }

        if (steps == 2) {
            return finish(beam2, beam, ux, uy, uvx, uvy, speed, accel, drag, beam1, null);
        }

        // ---------- STEP 3 ----------
        resetBeam(beam3, beam);
        for (int p = 0; p < beam; p++) {
            BeamEntry parent = beam2[p];
            if (!Float.isFinite(parent.score)) continue;
            expandBeam(parent, beam3, beam, samples, p, 2*H, speed, accel, drag, pivot);
        }

        return finish(beam3, beam, ux, uy, uvx, uvy, speed, accel, drag, beam2, beam1);
    }

    /** Расширение одного узла beam: from parent (endX, endY, endVx, endVy) к child beam. */
    private void expandBeam(BeamEntry parent, BeamEntry[] childBeam, int beam,
                            int samples, int parentIdx, int bulletOffset,
                            float speed, float accel, float drag, Vec2 pivot) {
        // baseline продолжения: ничего не делать
        Vec2 endVel = simulate(parent.endX, parent.endY, parent.endVx, parent.endVy,
                               0f, 0f, speed, accel, drag);
        float baseDanger = scoreSim(bulletOffset);
        float baseBias = pivotBias(simX[H], simY[H], pivot);
        insertBeam(childBeam, beam, parent.score + baseDanger + baseBias, 0f, 0f,
                   simX[H], simY[H], endVel.x, endVel.y, parentIdx);

        for (int k = 0; k < samples; k++) {
            float a = k * 2f * Mathf.PI / samples;
            float dx = Mathf.cos(a) * speed;
            float dy = Mathf.sin(a) * speed;
            endVel = simulate(parent.endX, parent.endY, parent.endVx, parent.endVy,
                              dx, dy, speed, accel, drag);
            float danger = scoreSim(bulletOffset);
            float bias = pivotBias(simX[H], simY[H], pivot);
            float cont = fHysteresis ? -((dx*parent.dx + dy*parent.dy) / (speed*speed)) * HYST_WEIGHT * 0.3f : 0f;
            insertBeam(childBeam, beam, parent.score + danger + bias + cont, dx, dy,
                       simX[H], simY[H], endVel.x, endVel.y, parentIdx);
        }
    }

    /** Берём beam[0] (минимум), trace ancestors, применяем первый шаг. */
    private Vec2 finish(BeamEntry[] finalBeam, int beam,
                        float ux, float uy, float uvx, float uvy,
                        float speed, float accel, float drag,
                        BeamEntry[] mid, BeamEntry[] first) {
        BeamEntry winner = finalBeam[0];
        if (!Float.isFinite(winner.score)) {
            prevDx = 0; prevDy = 0;
            return evade;
        }
        // traceback: до beam1[ancestor] чьё (dx, dy) — наш первый шаг
        BeamEntry rootStep;
        if (mid == null) {
            rootStep = winner;
        } else if (first == null) {
            rootStep = mid[winner.ancestor];
        } else {
            BeamEntry midEntry = mid[winner.ancestor];
            rootStep = first[midEntry.ancestor];
        }

        bestDanger = winner.score;

        if (rootStep.dx == 0f && rootStep.dy == 0f) {
            prevDx = 0; prevDy = 0;
            return evade;
        }

        simulate(ux, uy, uvx, uvy, rootStep.dx, rootStep.dy, speed, accel, drag);
        threatCount = countSimHits(0f);

        prevDx = rootStep.dx;
        prevDy = rootStep.dy;
        evade.set(rootStep.dx, rootStep.dy);
        return evade;
    }

    private Vec2 simulate(float startX, float startY, float startVx, float startVy,
                          float dx, float dy, float speed, float accel, float drag) {
        if (!fPhysics) {
            // build 7 поведение: vel = (dx,dy) мгновенно, прямолинейная экстраполяция
            float px = startX, py = startY;
            simX[0] = px; simY[0] = py;
            for (int t = 1; t <= H; t++) {
                px += dx;
                py += dy;
                simX[t] = px;
                simY[t] = py;
            }
            retVel.set(dx, dy);
            return retVel;
        }
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
            int maxT;
            if (fLifetime) {
                float remain = b.type.lifetime - b.time - bulletTimeOffset;
                maxT = (int) Math.min(H, Math.max(0, remain));
                if (maxT <= 0) continue;
            } else {
                maxT = H;
            }
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
            float wDam = fDamageWeight ? Mathf.clamp(b.damage / 15f, 0.5f, 5f) : 1f;
            total += (SAFE_R - dCPA) * (REACT_HORIZON - minT) / REACT_HORIZON * wDam;
        }
        return total;
    }

    private int countSimHits(float bulletTimeOffset) {
        int n = 0;
        for (int i = 0; i < nearby.size; i++) {
            Bullet b = nearby.get(i);
            int maxT;
            if (fLifetime) {
                float remain = b.type.lifetime - b.time - bulletTimeOffset;
                maxT = (int) Math.min(H, Math.max(0, remain));
                if (maxT <= 0) continue;
            } else {
                maxT = H;
            }
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

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
