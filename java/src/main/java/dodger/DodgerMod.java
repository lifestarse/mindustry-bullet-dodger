// Build: 18
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
            // тумблеры post-7 фич — выключаем по одному для диагностики
            // physics: ВЫКЛ по умолчанию, т.к. vel.set игнорит accel. Включать только с movePref.
            t.checkPref("dodger.physics",      false); // build 8: физическая симуляция accel/drag
            t.checkPref("dodger.lifetime",     true);  // build 8: учёт оставшегося времени жизни пуль
            t.checkPref("dodger.hysteresis",   true);  // build 8: continuity бонус
            t.checkPref("dodger.damageWeight", true);  // build 8: вес угрозы по урону пули
            t.checkPref("dodger.densityCap",   true);  // build 8: штраф pivot-score за плотность турелей
            t.checkPref("dodger.homingSim",    true);  // build 14: итеративная симуляция homing-пуль
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

    private boolean isEnabled()        { return Core.settings.getBool(KEY_ENABLED, false); }
    private boolean useMovePref()      { return Core.settings.getBool(KEY_MOVE_PREF, false); }
    private boolean isPassive()        { return Core.settings.getBool(KEY_PASSIVE, false); }
    private void    setEnabled(boolean v) { Core.settings.put(KEY_ENABLED, v); }

    /**
     * Заполняет dodger.zones списком no-dodge зон вокруг юнита:
     *   - DEATH-турели по их range (+ запас на хитбокс)
     *   - вражеские NO_DODGE юниты по их max weapon range
     */
    private void populateZones(Unit unit) {
        dodger.clearZones();
        final float buffer = unit.type.hitSize * 0.5f + 4f;
        // турели
        for (Teams.TeamData td : Vars.state.teams.getActive()) {
            if (td.team == unit.team) continue;
            Vars.indexer.eachBlock(td.team, unit.x, unit.y, PivotPlanner.SCAN_R, b -> true, b -> {
                if (!(b instanceof Turret.TurretBuild tb)) return;
                if (TurretCatalog.classify(b.block) == TurretCatalog.Kind.DEATH) {
                    float r = ((Turret) b.block).range + buffer;
                    dodger.addZone(b.x, b.y, r);
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

        // PASSIVE MODE: мод не баитит, ничего не строит, просто перехватывает движение
        // когда летят пули. В остальное время игрок управляет сам.
        if (isPassive()) {
            populateZones(unit);
            Vec2 evade = dodger.compute(unit, null);  // null pivot = без bias
            if (evade.len2() > 0.01f) {
                if (useMovePref()) {
                    try { unit.movePref(evade); } catch (Throwable t) { unit.vel.set(evade); }
                } else {
                    unit.vel.set(evade);
                }
                lastWasEvade = true;
                lastFinal.set(evade);
            } else {
                lastWasEvade = false;
                lastFinal.setZero();
            }
            // лог
            if (++ticksSinceLog >= LOG_PERIOD_TICKS) {
                Log.info(String.format(
                    "[dodger PASSIVE] scanned=%d enemy=%d hits-after-evade=%d danger=%.2f | mode=%s | move=(%.2f,%.2f) vel=(%.2f,%.2f)",
                    dodger.bulletsScanned, dodger.enemyBullets, dodger.threatCount, dodger.bestDanger,
                    lastWasEvade ? "EVADE" : "IDLE",
                    lastFinal.x, lastFinal.y, unit.vel.x, unit.vel.y));
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
        Vec2 evade = dodger.compute(unit, driftedPivot);
        Vec2 finalMove;
        if (evade.len2() > 0.01f) {
            finalMove = evade;
            lastWasEvade = true;
        } else {
            finalMove = orbiter.step(unit, driftedPivot);
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
