// Build: 20
package dodger;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import mindustry.entities.units.AIController;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;

/**
 * Кастомный UnitController, заменяющий Player для drone'а в hunt-режиме.
 *
 * Устанавливаем как unit.controller(this), и Mindustry будет вызывать update() этого
 * объекта вместо Player.update — мы получаем полный контроль над движением, прицеливанием
 * и стрельбой, без интерференции от курсора игрока.
 *
 * DodgerMod каждый тик пишет сюда target / desiredMove / wantShoot, а update()
 * применяет эти значения к unit.
 */
public final class HuntController extends AIController {

    private Teamc target;
    private final Vec2 desiredMove = new Vec2();
    private boolean wantShoot = false;

    public void setTarget(Teamc t)   { this.target = t; }
    public void setMove(Vec2 v)       { this.desiredMove.set(v); }
    public void setShooting(boolean s){ this.wantShoot = s; }

    @Override
    public void updateUnit() {
        if (unit == null || unit.dead) return;

        // движение: пишем напрямую vel (instant override)
        unit.vel.set(desiredMove);

        // прицеливание + поворот корпуса к цели
        if (target != null) {
            float tx = target.getX();
            float ty = target.getY();
            unit.aim(tx, ty);
            unit.rotation = unit.angleTo(tx, ty);
        }
    }

    @Override
    public boolean shouldShoot() {
        return wantShoot && target != null;
    }

    public Teamc target() {
        return target;
    }
}
