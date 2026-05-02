// Build: 4
package dodger;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import mindustry.gen.Unit;

/**
 * Возврат к pivot-точке. Никакой орбиты.
 *
 * Юнит должен стоять на pivot и срываться только когда BulletDodger возвращает
 * вектор уклонения. Здесь — только медленный возврат к точке стоянки.
 *
 * - дальше DEAD_ZONE — целимся точно в pivot на полной скорости.
 * - в DEAD_ZONE — стоим на месте.
 */
public final class OrbitController {

    private static final float DEAD_ZONE = 4f; // px, "уже стоим"

    private final Vec2 desired = new Vec2();

    public Vec2 step(Unit unit, Vec2 pivot) {
        desired.setZero();
        if (unit == null || unit.dead || pivot == null) return desired;

        float dx = pivot.x - unit.x;
        float dy = pivot.y - unit.y;
        float r  = Mathf.sqrt(dx*dx + dy*dy);

        if (r < DEAD_ZONE) return desired; // уже на месте

        float speed = unit.type.speed;
        desired.set(dx / r * speed, dy / r * speed);
        return desired;
    }

    public void reset() { /* без состояния */ }
}
