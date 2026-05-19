// Build: 19
package dodger;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Teams;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.mod.Mod;
import mindustry.world.blocks.defense.turrets.Turret;

/**
 * Диагностический build.
 *
 * Каждый тик:
 *   - решаем, какой вектор движения хотим (evade или return-to-pivot)
 *   - применяем его через unit.movePref(...) ИЛИ unit.vel.set(...) в зависимости от режима.
 *     Сейчас по умолчанию — vel.set с принудительным вызовом каждый тик; movePref-режим
 *     включается через настройку.
 *   - раз в 60 тиков пишем в лог состояние: pivot, threats, evade, vel, pos, score
 *   - на Trigger.draw (если ивент существует) рисуем pivot, орбитал-вектор, угрозы.
 */
public class DodgerMod extends Mod {

    private static final String KEY_ENABLED   = "dodger.enabled";
    private static final String KEY_MOVE_PREF = "dodger.movePref";
    private static final String KEY_PASSIVE   = "dodger.passive";
    private static final String KEY_HUNT      = "dodger.hunt";
    private static final float  HUNT_RANGE    = 600f;
    private static final int    REPLAN_FALLBACK_TICKS = 60;
    private static final int    LOG_PERIOD_TICKS = 60;

    private final PivotPlanner    planner = new PivotPlanner();
    private final OrbitController orbiter = new OrbitController();
    private final BulletDodger    dodger  = new BulletDodger();

    private boolean replanPending = true;
    private int     ticksSinceReplan = 0;
    private int     ticksSinceLog = 0;

    private final Vec2 lastFinal     = new Vec2();
    private final Vec2 driftedPivot  = new Vec2();
    private float      driftPhase    = 0f;
    private boolean    lastWasEvade  = false;

    // === PASSIVE MODE STATE ===
    private final Vec2 passiveAnchor    = new Vec2();
    private boolean    hasPassiveAnchor = false;
    private final Vec2 passiveIntent    = new Vec2();
    private final Vec2 passiveZero      = new Vec2();
    private final Vec2 inputBuf         = new Vec2();
    /** Совпадает с OrbitController.DEAD_ZONE — порог "уже на anchor". */
    private static final float ANCHOR_DEAD_ZONE = 4f;

    // === STATISTICS ===
    private float lastHp = -1f;
    private int   currentStreak  = 0;   // тиков подряд в EVADE без урона
    private int   bestStreak     = 0;
    private int   totalHits      = 0;
    private int   totalEvades    = 0;
    private int   totalDeaths    = 0;
    private long  totalScore     = 0;
    private int   ticksAlive     = 0;
    private int   lastUnitId     = -1;
    private static final int   STREAK_MILESTONE = 60;   // каждые 1с — лог
    private static final int   SUMMARY_PERIOD   = 600;  // каждые 10с — summary
    private static final long  HIT_PENALTY      = 100;
    private static final long  DEATH_PENALTY    = 1000;

    // Lissajous: непериодичный по эффекту дрейф (периоды 30/45 не совпадают)
    private static final float DRIFT_R         = 40f;
    private static final float DRIFT_PERIOD_X  = 30f;
    private static final float DRIFT_PERIOD_Y  = 45f;

    @Override
    public void init() {
        Vars.ui.settings.addCategory("Dodger", t -> {
            t.checkPref(KEY_ENABLED, false);
            t.checkPref(KEY_MOVE_PREF, false);
            // passive: мод не баитит, не управляет — но перехватывает движение когда летит пуля.
            t.checkPref(KEY_PASSIVE, false);
            // hunt: дрон сам преследует ближайшего вражеского юнита/игрока, стреляет, доджит.
            t.checkPref(KEY_HUNT, false);
            // тюнинг beam search'а — точность vs CPU
            t.sliderPref("dodger.samples",   BulletDodger.DEFAULT_SAMPLES,    90, 7200, 90,
                v -> v + " dirs/step");
            t.sliderPref("dodger.steps",     BulletDodger.DEFAULT_STEPS,       1,   3,  1,
                v -> v + " step" + (v == 1 ? "" : "s"));
            t.sliderPref("dodger.beamWidth", BulletDodger.DEFAULT_BEAM_WIDTH,  1,  16,  1,
                v -> "beam " + v);
            // ебанутость: минимальная безопасная дистанция от pivot до турели.
            // 20 px — суицид (3 тайла, борода с турелью), 120 — безопаснее (15 тайлов).
            t.sliderPref("dodger.minDist", PivotPlanner.DEFAULT_MIN_DIST,     0, 120,  5,
                v -> (v == 0 ? "off" : v + " px") + " (0 = нет ограничения)");
            // aggression: % бонуса за близость к стволу. 100 = дефолт, 0 = безразлично к близости.
            t.sliderPref("dodger.aggression", 100, 0, 200, 10,
                v -> v + "% closeness reward");
            // edge penalty: штраф за качание на пределе радиуса. 0 = выкл, 100 = дефолт.
            t.sliderPref("dodger.edgePenalty", 100, 0, 200, 10,
                v -> v == 0 ? "off" : v + "% edge band");
            // safetyMargin: запас (px) поверх реальной hit-зоны при оценке столкновения.
            // Чем больше — тем шире "недопустимое сближение", меньше грейзов, но больше evade'ов.
            t.sliderPref("dodger.safetyMargin", 5, 0, 20, 1,
                v -> v + " px hit margin");
            // тумблеры post-7 фич — выключаем по одному для диагностики
            // physics: ВЫКЛ по умолчанию, т.к. vel.set игнорит accel. Включать только с movePref.
            t.checkPref("dodger.physics",      false); // build 8: физическая симуляция accel/drag
            t.checkPref("dodger.lifetime",     true);  // build 8: учёт оставшегося времени жизни пуль
            t.checkPref("dodger.hysteresis",   true);  // build 8: continuity бонус
            t.checkPref("dodger.damageWeight", true);  // build 8: вес угрозы по урону пули
            t.checkPref("dodger.densityCap",   true);  // build 8: штраф pivot-score за плотность турелей
            t.checkPref("dodger.homingSim",    false); // build 14: итеративная симуляция homing-пуль (опт-ин, пока нестабильно)
            t.checkPref("dodger.subtick",      true);  // build 14: sub-tick CPA через параболу
            t.checkPref("dodger.preferMotion", true);  // build 17: бонус за продолжение текущего вектора движения
            t.checkPref("dodger.drift",        false); // build 18: Lissajous-дрейф pivot'а; OFF = статика
            // ping compensation: сдвигает положение пуль вперёд на RTT.
            t.checkPref("dodger.pingAuto", true);  // если в multiplayer — берём пинг из netClient
            t.sliderPref("dodger.pingMs", 0, 0, 500, 10,
                v -> v == 0 ? "off" : v + " ms (manual)");

            // === Цели для байта: какие турели стрелять/уворачиваться ===
            t.add("[accent]Bait turrets:").left().row();
            for (String n : TurretCatalog.BAIT_DEFAULT) {
                t.checkPref("dodger.bait." + n, true);  // default ON
            }
            t.add("[lightgray]Optional (рискованные):").left().row();
            for (String n : TurretCatalog.BAIT_OPTIONAL) {
                t.checkPref("dodger.bait." + n, false); // default OFF
            }
        });

        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if (e == null || e.tile == null) return;
            if (Vars.player == null || Vars.player.unit() == null) return;
            float dx = e.tile.worldx() - Vars.player.unit().x;
            float dy = e.tile.worldy() - Vars.player.unit().y;
            if (dx*dx + dy*dy > PivotPlanner.SCAN_R * PivotPlanner.SCAN_R) return;
            replanPending = true;
        });

        Events.on(EventType.BlockDestroyEvent.class, e -> {
            if (e == null || e.tile == null) return;
            if (Vars.player == null || Vars.player.unit() == null) return;
            float dx = e.tile.worldx() - Vars.player.unit().x;
            float dy = e.tile.worldy() - Vars.player.unit().y;
            if (dx*dx + dy*dy > PivotPlanner.SCAN_R * PivotPlanner.SCAN_R) return;
            replanPending = true;
        });

        Events.run(EventType.Trigger.update, this::tick);
        // визуальный оверлей
        try {
            Events.run(EventType.Trigger.draw, this::drawDebug);
        } catch (Throwable t) {
            Log.warn("[dodger] Trigger.draw недоступен: " + t.getMessage());
        }
    }

    /** Updates statistics based on this tick's outcome. */
    private void updateStats(Unit unit) {
        // detect respawn (new unit instance) → death event
        if (lastUnitId != -1 && unit.id != lastUnitId) {
            totalDeaths++;
            totalScore -= DEATH_PENALTY;
            arc.util.Log.info(String.format(
                "[STAT] DEATH #%d streak_at_death=%d best=%d score=%d",
                totalDeaths, currentStreak, bestStreak, totalScore));
            currentStreak = 0;
            lastHp = -1f;
        }
        lastUnitId = unit.id;

        if (lastHp < 0f) { lastHp = unit.health; return; }
        float cur = unit.health;
        if (cur < lastHp - 0.5f) {
            // получили урон
            float dmg = lastHp - cur;
            totalHits++;
            totalScore -= HIT_PENALTY;
            if (currentStreak > bestStreak) bestStreak = currentStreak;
            arc.util.Log.info(String.format(
                "[STAT] HIT #%d dmg=%.1f streak_broken=%d best=%d score=%d | mode=%s pred_danger=%.2f hits-after-evade=%d",
                totalHits, dmg, currentStreak, bestStreak, totalScore,
                lastWasEvade ? "EVADE" : "GOTO", dodger.bestDanger, dodger.threatCount));
            currentStreak = 0;
        } else if (lastWasEvade) {
            currentStreak++;
            totalEvades++;
            totalScore++;
            if (currentStreak > 0 && currentStreak % STREAK_MILESTONE == 0) {
                arc.util.Log.info(String.format(
                    "[STAT] +%dt streak (best=%d) score=%d",
                    currentStreak, Math.max(currentStreak, bestStreak), totalScore));
            }
        }
        lastHp = cur;

        ticksAlive++;
        if (ticksAlive % SUMMARY_PERIOD == 0) {
            arc.util.Log.info(String.format(
                "[STAT] summary: score=%d | %d hits, %d deaths, %d evade-ticks | curr_streak=%d best=%d",
                totalScore, totalHits, totalDeaths, totalEvades, currentStreak, bestStreak));
        }
    }

    private boolean isEnabled()        { return Core.settings.getBool(KEY_ENABLED, false); }
    private boolean useMovePref()      { return Core.settings.getBool(KEY_MOVE_PREF, false); }
    private boolean isPassive()        { return Core.settings.getBool(KEY_PASSIVE, false); }
    private boolean isHunt()           { return Core.settings.getBool(KEY_HUNT, false); }
    private void    setEnabled(boolean v) { Core.settings.put(KEY_ENABLED, v); }

    /** Намерение игрока для passive: единичный WASD-вектор × unit.speed.
     *  Длина 0 если WASD не зажаты. Mouse/touch не читаются — passive интерпретирует
     *  отсутствие WASD как "игрок стоит", выставляет anchor. */
    private Vec2 readPlayerIntent(Unit unit) {
        inputBuf.setZero();
        if (Core.input == null) return inputBuf;
        float dx = 0f, dy = 0f;
        if (Core.input.keyDown(KeyCode.w)) dy += 1f;
        if (Core.input.keyDown(KeyCode.s)) dy -= 1f;
        if (Core.input.keyDown(KeyCode.d)) dx += 1f;
        if (Core.input.keyDown(KeyCode.a)) dx -= 1f;
        if (dx == 0f && dy == 0f) return inputBuf;
        float len = arc.math.Mathf.sqrt(dx*dx + dy*dy);
        float speed = unit.type.speed;
        inputBuf.set(dx / len * speed, dy / len * speed);
        return inputBuf;
    }

    /** Поиск ближайшего враждебного юнита в HUNT_RANGE. */
    private Unit findHuntTarget(Unit me) {
        Unit[] best = { null };
        float[] bestD2 = { HUNT_RANGE * HUNT_RANGE };
        Groups.unit.each(u -> {
            if (u == null || u.dead) return;
            if (u == me) return;
            if (u.team == me.team) return;
            float dx = u.x - me.x, dy = u.y - me.y;
            float d2 = dx*dx + dy*dy;
            if (d2 < bestD2[0]) { bestD2[0] = d2; best[0] = u; }
        });
        return best[0];
    }

    /**
     * Заполняет dodger.zones списком no-dodge зон вокруг юнита:
     *   - DEATH-турели по их range (+ запас на хитбокс)
     *   - вражеские NO_DODGE юниты по их max weapon range
     */
    private void populateZones(Unit unit) {
        dodger.clearZones();
        dodger.clearEdges();
        final float buffer = unit.type.hitSize * 0.5f + 4f;
        // турели
        for (Teams.TeamData td : Vars.state.teams.getActive()) {
            if (td.team == unit.team) continue;
            Vars.indexer.eachBlock(td.team, unit.x, unit.y, PivotPlanner.SCAN_R, b -> true, b -> {
                if (!(b instanceof Turret.TurretBuild tb)) return;
                TurretCatalog.Kind k = TurretCatalog.classify(b.block);
                if (k == TurretCatalog.Kind.DEATH) {
                    float r = ((Turret) b.block).range + buffer;
                    dodger.addZone(b.x, b.y, r);
                } else if (k == TurretCatalog.Kind.BAITABLE) {
                    // edge band вокруг радиуса: drone не должен качаться на пределе
                    dodger.addEdge(b.x, b.y, ((Turret) b.block).range);
                }
            });
        }
        // юниты с не-доджибельным оружием
        Groups.unit.each(u -> {
            if (u == null || u.dead) return;
            if (u.team == unit.team) return;
            float dx = u.x - unit.x, dy = u.y - unit.y;
            float scan = PivotPlanner.SCAN_R;
            if (dx*dx + dy*dy > scan*scan) return;
            if (UnitCatalog.isNoDodge(u.type)) {
                dodger.addZone(u.x, u.y, u.range() + buffer);
            }
        });
    }

    private void tick() {
        if (Core.input.ctrl() && Core.input.shift() && Core.input.keyTap(KeyCode.b)) {
            boolean now = !isEnabled();
            setEnabled(now);
            try { Vars.ui.hudfrag.showToast("[dodger] " + (now ? "ON" : "OFF")); }
            catch (Throwable ignored) { Log.info("[dodger] " + (now ? "ON" : "OFF")); }
        }

        if (!isEnabled()) return;
        if (Vars.player == null) return;
        Unit unit = Vars.player.unit();
        if (unit == null || unit.dead) return;

        // single-shot stats per tick — после фактической работы dodger'а
        updateStats(unit);

        // PASSIVE MODE: мод не баитит, ничего не строит. Логика уклонения — та же, что
        // active против турелей: compute(unit, pivot, intended).
        //
        //   игрок ДВИГАЛСЯ (WASD)  → intent = WASD-вектор, pivot = null.
        //                            evade пытается сохранить направление через motion bonus.
        //                            если безопасно — не перезаписываем vel, игрок рулит сам.
        //   игрок СТОЯЛ            → anchor = позиция в момент перехода. pivot = anchor,
        //                            intent = возврат к anchor (если drone снесло evade-ом).
        //                            если безопасно — возвращаемся к anchor.
        //
        // Anchor сбрасывается при возобновлении WASD.
        if (isPassive()) {
            populateZones(unit);

            Vec2 intent = readPlayerIntent(unit);
            boolean playerMoving = intent.len2() > 0.01f;

            if (playerMoving) {
                hasPassiveAnchor = false;
            } else if (!hasPassiveAnchor) {
                passiveAnchor.set(unit.x, unit.y);
                hasPassiveAnchor = true;
            }

            Vec2 pivot;
            Vec2 intended;
            if (playerMoving) {
                pivot = null;
                intended = intent;
            } else if (hasPassiveAnchor) {
                pivot = passiveAnchor;
                float adx = passiveAnchor.x - unit.x;
                float ady = passiveAnchor.y - unit.y;
                float ar  = arc.math.Mathf.sqrt(adx*adx + ady*ady);
                if (ar > ANCHOR_DEAD_ZONE) {
                    float speed = unit.type.speed;
                    intended = passiveIntent.set(adx / ar * speed, ady / ar * speed);
                } else {
                    intended = null;
                }
            } else {
                pivot = null;
                intended = null;
            }

            Vec2 evade = dodger.compute(unit, pivot, intended);
            Vec2 finalMove = null;
            String modeTag;

            if (evade.len2() > 0.01f) {
                finalMove = evade;
                lastWasEvade = true;
                modeTag = "EVADE";
            } else if (dodger.preferStandStill) {
                finalMove = passiveZero.setZero();
                lastWasEvade = true;
                modeTag = "FREEZE";
            } else if (!playerMoving && intended != null) {
                finalMove = intended;
                lastWasEvade = false;
                modeTag = "RETURN";
            } else {
                // safe + player moving → не перезаписываем vel, игрок рулит сам.
                // safe + player not moving + at anchor → drone стоит, ничего не делаем.
                lastWasEvade = false;
                modeTag = playerMoving ? "PASS" : "IDLE";
            }

            if (finalMove != null) {
                if (useMovePref()) {
                    try { unit.movePref(finalMove); } catch (Throwable t) { unit.vel.set(finalMove); }
                } else {
                    unit.vel.set(finalMove);
                }
                lastFinal.set(finalMove);
            } else {
                lastFinal.setZero();
            }

            if (++ticksSinceLog >= LOG_PERIOD_TICKS) {
                Log.info(String.format(
                    "[dodger PASSIVE] scanned=%d enemy=%d hits-after-evade=%d danger=%.2f | mode=%s anchor=%s | move=(%.2f,%.2f) vel=(%.2f,%.2f)",
                    dodger.bulletsScanned, dodger.enemyBullets, dodger.threatCount, dodger.bestDanger,
                    modeTag,
                    hasPassiveAnchor ? String.format("(%.0f,%.0f)", passiveAnchor.x, passiveAnchor.y) : "none",
                    lastFinal.x, lastFinal.y, unit.vel.x, unit.vel.y));
                ticksSinceLog = 0;
            }
            return;
        }

        // HUNT MODE: преследуем цель и стреляем по ней, доджим параллельно.
        // Player остаётся controller'ом (не меняем — иначе respawn loop), всё через
        // прямые перезаписи unit/Player полей в Trigger.update.
        if (isHunt()) {
            Unit target = findHuntTarget(unit);
            populateZones(unit);
            Vec2 evade = dodger.compute(unit, target == null ? null : new Vec2(target.x, target.y));
            Vec2 finalMove;
            if (evade.len2() > 0.01f) {
                finalMove = evade;
                lastWasEvade = true;
            } else if (target != null) {
                float dx = target.x - unit.x, dy = target.y - unit.y;
                float r  = arc.math.Mathf.sqrt(dx*dx + dy*dy);
                float keepDist = Math.max(40f, unit.range() * 0.7f);
                float speed = unit.type.speed;
                if (r > keepDist + 4f) {
                    finalMove = lastFinal.set(dx / r * speed, dy / r * speed);
                } else {
                    finalMove = lastFinal.setZero();
                }
                lastWasEvade = false;
            } else {
                finalMove = lastFinal.setZero();
                lastWasEvade = false;
            }
            lastFinal.set(finalMove);
            unit.vel.set(finalMove);

            // прицеливание + стрельба
            if (target != null) {
                Vars.player.mouseX = target.x;
                Vars.player.mouseY = target.y;
                Vars.player.shooting = true;
                unit.aim(target.x, target.y);
                // unit.angleTo использует Mindustry-конвенцию (0=север), а не math (0=восток).
                unit.rotation = unit.angleTo(target.x, target.y);
                try {
                    for (var mount : unit.mounts) {
                        mount.shoot = true;
                        mount.rotate = true;
                        mount.aimX = target.x;
                        mount.aimY = target.y;
                    }
                } catch (Throwable t) { }
            } else {
                Vars.player.shooting = false;
            }

            if (++ticksSinceLog >= LOG_PERIOD_TICKS) {
                Log.info(String.format(
                    "[dodger HUNT] target=%s dist=%.0f | scanned=%d enemy=%d threats=%d danger=%.2f | mode=%s",
                    target != null ? target.type.name : "none",
                    target != null ? arc.math.Mathf.dst(target.x, target.y, unit.x, unit.y) : 0f,
                    dodger.bulletsScanned, dodger.enemyBullets, dodger.threatCount, dodger.bestDanger,
                    lastWasEvade ? "EVADE" : "CHASE"));
                ticksSinceLog = 0;
            }
            return;
        }

        ticksSinceReplan++;
        if (replanPending || ticksSinceReplan >= REPLAN_FALLBACK_TICKS) {
            planner.replan(unit.x, unit.y, unit.team);
            replanPending = false;
            ticksSinceReplan = 0;
        }

        if (planner.currentPivot == null) {
            if (++ticksSinceLog >= LOG_PERIOD_TICKS) {
                Log.info("[dodger] no pivot. enemy bullets nearby would be detected next time.");
                ticksSinceLog = 0;
            }
            return;
        }

        // дрейфующая цель: pivot + Lissajous offset (если включено), иначе статика
        if (Core.settings.getBool("dodger.drift", false)) {
            driftPhase += 1f;
            driftedPivot.set(
                planner.currentPivot.x + DRIFT_R * arc.math.Mathf.sin(driftPhase / DRIFT_PERIOD_X),
                planner.currentPivot.y + DRIFT_R * arc.math.Mathf.cos(driftPhase / DRIFT_PERIOD_Y)
            );
        } else {
            driftedPivot.set(planner.currentPivot);
        }

        populateZones(unit);
        // сначала вычисляем GOTO направление к pivot, передаём его в compute как "intended"
        // — bullet dodger проверит опасность движения В pivot, а не только стояния на месте.
        Vec2 gotoMove = orbiter.step(unit, driftedPivot);
        Vec2 evade = dodger.compute(unit, driftedPivot, gotoMove);
        Vec2 finalMove;
        if (evade.len2() > 0.01f) {
            finalMove = evade;
            lastWasEvade = true;
        } else if (dodger.preferStandStill) {
            // стоять безопаснее чем GOTO в pivot — не применяем GOTO
            finalMove = lastFinal.setZero();
            lastWasEvade = true;  // считаем как evade (защитное действие)
        } else {
            finalMove = gotoMove;
            lastWasEvade = false;
        }
        lastFinal.set(finalMove);

        // применение движения
        if (useMovePref()) {
            try { unit.movePref(finalMove); }
            catch (Throwable t) { unit.vel.set(finalMove); }
        } else {
            unit.vel.set(finalMove);
        }

        // диагностика
        if (++ticksSinceLog >= LOG_PERIOD_TICKS) {
            Vec2 p = planner.currentPivot;
            Log.info(String.format(
                "[dodger] pivot=(%.0f,%.0f) score=%.1f | beam=%dx%d (%d steps) | scanned=%d enemy=%d hits-after-evade=%d danger=%.2f | mode=%s | move=(%.2f,%.2f) vel=(%.2f,%.2f) unit=(%.0f,%.0f)",
                p.x, p.y, planner.currentScore,
                dodger.lastSamples, dodger.lastBeam, dodger.lastSteps,
                dodger.bulletsScanned, dodger.enemyBullets, dodger.threatCount, dodger.bestDanger,
                lastWasEvade ? "EVADE" : "GOTO",
                finalMove.x, finalMove.y,
                unit.vel.x, unit.vel.y,
                unit.x, unit.y));
            ticksSinceLog = 0;
        }
    }

    private void drawDebug() {
        if (!isEnabled()) return;
        if (Vars.player == null || Vars.player.unit() == null) return;
        if (planner.currentPivot == null) return;

        Unit unit = Vars.player.unit();
        Vec2 pivot = planner.currentPivot;

        Draw.z(120f); // overlay above world

        // ideal pivot — мелкий белый крест
        Draw.color(Color.white);
        Lines.stroke(1f);
        Lines.line(pivot.x - 3, pivot.y, pivot.x + 3, pivot.y);
        Lines.line(pivot.x, pivot.y - 3, pivot.x, pivot.y + 3);

        // текущая цель (дрейфующая) — красный круг
        Draw.color(Color.red);
        Lines.stroke(2f);
        Lines.circle(driftedPivot.x, driftedPivot.y, 6f);
        Fill.circle(driftedPivot.x, driftedPivot.y, 2.5f);

        // вектор движения от юнита
        Draw.color(lastWasEvade ? Color.yellow : Color.green);
        Lines.line(unit.x, unit.y, unit.x + lastFinal.x * 4f, unit.y + lastFinal.y * 4f);

        Draw.reset();
    }
}
