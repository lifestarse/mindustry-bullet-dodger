// Build: 14
package dodger;

import arc.Core;
import arc.math.Angles;
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
    private static final float SCAN_R        = 200f;
    private static final int   BEAM_MAX      = 16;
    private static final float PIVOT_BIAS    = 0.01f;
    private static final float HYST_WEIGHT   = 0.5f;
    private static final float SAFETY_MARGIN = 2f;   // px поверх реальной hit-зоны

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
    private boolean fHoming;     // итеративная симуляция homing-пуль (build 14)
    private boolean fSubtick;    // sub-tick точность через параболическую интерполяцию

    /** Радиус юнита (hitSize/2). Кэшируется в начале compute. */
    private float unitR;

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
        fHoming       = Core.settings.getBool("dodger.homingSim",    true);
        fSubtick      = Core.settings.getBool("dodger.subtick",      true);
        unitR = unit.type.hitSize * 0.5f;
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
        boolean iterateHoming = fHoming && bulletTimeOffset == 0f;
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

            // per-bullet threshold по реальному размеру пули
            float bulletR = b.type.hitSize;
            if (bulletR < 1f) bulletR = 4f;
            float thr = unitR + bulletR + SAFETY_MARGIN;
            float thr2 = thr * thr;

            float minD2; float minTf;
            boolean homing = iterateHoming && b.type.homingPower > 1e-4f;
            if (homing) {
                long packed = simHoming(b, maxT, thr2);
                minD2 = Float.intBitsToFloat((int)(packed >>> 32));
                minTf = Float.intBitsToFloat((int)(packed & 0xFFFFFFFFL));
            } else {
                long packed = simLinearSubtick(b, bulletTimeOffset, maxT);
                minD2 = Float.intBitsToFloat((int)(packed >>> 32));
                minTf = Float.intBitsToFloat((int)(packed & 0xFFFFFFFFL));
            }

            if (minD2 >= thr2) continue;
            float dCPA = Mathf.sqrt(minD2);
            float wDam = fDamageWeight ? Mathf.clamp(b.damage / 15f, 0.5f, 5f) : 1f;
            total += (thr - dCPA) * (REACT_HORIZON - minTf) / REACT_HORIZON * wDam;
        }
        return total;
    }

    /**
     * Линейная симуляция пули с sub-tick CPA через минимум на каждом отрезке [t,t+1].
     * Возвращает (minD2, minT) упакованное в long.
     */
    private long simLinearSubtick(Bullet b, float bulletTimeOffset, int maxT) {
        float minD2 = Float.POSITIVE_INFINITY;
        float minTf = 0f;
        float bvx = b.vel.x, bvy = b.vel.y;
        for (int t = 0; t < maxT; t++) {
            float bxA = b.x + bvx * (bulletTimeOffset + t);
            float byA = b.y + bvy * (bulletTimeOffset + t);
            float bxB = bxA + bvx;
            float byB = byA + bvy;
            float sxA = simX[t],   syA = simY[t];
            float sxB = simX[t+1], syB = simY[t+1];
            // diff(s) = (sxA - bxA + s*((sxB-sxA)-(bxB-bxA)), ...) для s∈[0,1]
            float A = sxA - bxA, C = syA - byA;
            float B = (sxB - sxA) - (bxB - bxA);
            float D = (syB - syA) - (byB - byA);
            float vv = B*B + D*D;
            float s;
            if (vv < 1e-6f) s = 0;
            else            s = -(A*B + C*D) / vv;
            if (s < 0) s = 0;
            else if (s > 1) s = 1;
            float diffX = A + s*B;
            float diffY = C + s*D;
            float d2 = diffX*diffX + diffY*diffY;
            if (d2 < minD2) { minD2 = d2; minTf = t + s; }
            if (fSubtick == false) {
                // если sub-tick выключен — проверяем только endpoint каждого тика
                float dEx = bxA - sxA, dEy = byA - syA;
                float d2e = dEx*dEx + dEy*dEy;
                if (d2e < minD2) { minD2 = d2e; minTf = t; }
            }
        }
        // плюс последняя точка t=maxT
        float bxL = b.x + bvx * (bulletTimeOffset + maxT);
        float byL = b.y + bvy * (bulletTimeOffset + maxT);
        float dx = bxL - simX[maxT], dy = byL - simY[maxT];
        float d2 = dx*dx + dy*dy;
        if (d2 < minD2) { minD2 = d2; minTf = maxT; }
        return packFloats(minD2, minTf);
    }

    /**
     * Итеративная симуляция homing-пули: каждый тик vel поворачивается к юниту в пределах homingRange.
     * Mindustry: vel.setAngle(moveToward(currentAngle, targetAngle, homingPower * 50)) — градусы за тик.
     */
    private long simHoming(Bullet b, int maxT, float thr2) {
        float bx = b.x, by = b.y;
        float bvx = b.vel.x, bvy = b.vel.y;
        float homingRange = b.type.homingRange;
        float maxTurnDeg  = b.type.homingPower * 50f;
        float minD2 = Float.POSITIVE_INFINITY;
        float minTf = 0f;

        for (int t = 0; t <= maxT; t++) {
            float dx = bx - simX[t];
            float dy = by - simY[t];
            float d2 = dx*dx + dy*dy;
            if (d2 < minD2) { minD2 = d2; minTf = t; }

            if (t == maxT) break;

            // homing rotation если пуля в homingRange от юнита
            if (d2 < homingRange*homingRange) {
                float currAngleDeg   = Mathf.atan2(bvy, bvx) * Mathf.radDeg;
                float targetAngleDeg = Mathf.atan2(simY[t] - by, simX[t] - bx) * Mathf.radDeg;
                float newAngleDeg    = Angles.moveToward(currAngleDeg, targetAngleDeg, maxTurnDeg);
                float speedB = Mathf.sqrt(bvx*bvx + bvy*bvy);
                bvx = Mathf.cos(newAngleDeg * Mathf.degRad) * speedB;
                bvy = Mathf.sin(newAngleDeg * Mathf.degRad) * speedB;
            }
            bx += bvx;
            by += bvy;
        }
        return packFloats(minD2, minTf);
    }

    private static long packFloats(float a, float b) {
        return (((long) Float.floatToRawIntBits(a)) << 32) | (Float.floatToRawIntBits(b) & 0xFFFFFFFFL);
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
            float bulletR = b.type.hitSize;
            if (bulletR < 1f) bulletR = 4f;
            float thr = unitR + bulletR + SAFETY_MARGIN;
            float thr2 = thr * thr;
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
            if (minD2 < thr2) n++;
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
