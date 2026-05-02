// Bullet Dodger — авто-уклонение от пуль через предсказание траекторий.
//
// Алгоритм (выполняется каждый тик):
//   1. Берём управляемого юнита игрока (Vars.player.unit()).
//   2. Перебираем все Bullet'ы в Groups.bullet.
//   3. Фильтруем «свои» (bullet.team == unit.team) и слишком далёкие.
//   4. Для каждой опасной пули решаем задачу ближайшего сближения:
//        relPos = bulletPos - unitPos
//        relVel = bulletVel - unitVel
//        tCPA   = -(relPos · relVel) / |relVel|^2          // время ближайшего подхода
//        если tCPA < 0 или > horizon  -> пуля не угрожает
//        dCPA   = |relPos + relVel * tCPA|                  // мин. расстояние
//        если dCPA > safeRadius -> пуля не угрожает
//      Иначе считаем «угрозой» с весом w = (1 - dCPA/safeRadius) * (1 - tCPA/horizon).
//   5. Для каждой угрозы вычисляем перпендикуляр к relVel в сторону, уводящую от точки CPA,
//      суммируем взвешенно -> итоговый вектор уклонения.
//   6. Конвертируем в команду движения юнита: unit.movePref / unit.moveAt.
//
// Хук: Events.on(Trigger.update.getClass(), ...) не годится — Trigger это enum.
// Используем таймер через Timer.schedule, либо подписку на Events с EventType.Trigger.update.

const Vars      = Packages.mindustry.Vars;
const Groups    = Packages.mindustry.gen.Groups;
const Events    = Packages.arc.Events;
const Trigger   = Packages.mindustry.game.EventType.Trigger;
const Tmp       = Packages.arc.math.geom.Vec2;

// --- настройки ---
const HORIZON      = 1.2;   // секунд вперёд смотрим (60 tick = 1s)
const SAFE_RADIUS  = 28.0;  // тайлы * 8 = пиксели; ~3.5 тайла зона безопасности
const SCAN_RANGE   = 320.0; // не рассматриваем пули дальше этого
const DODGE_BOOST  = 1.4;   // насколько усиливать вектор уклонения

const evade = new Tmp();
const rel   = new Tmp();
const relV  = new Tmp();
const cpa   = new Tmp();
const perp  = new Tmp();

function tick() {
    const player = Vars.player;
    if (player == null) return;
    const unit = player.unit();
    if (unit == null || unit.dead) return;

    const ux = unit.x, uy = unit.y;
    const uvx = unit.vel.x, uvy = unit.vel.y;
    const tickRate = 60.0;

    evade.setZero();
    let totalWeight = 0;

    Groups.bullet.each(function(b){
        if (b == null || !b.isAdded()) return;
        if (b.team == unit.team) return;

        const dx = b.x - ux, dy = b.y - uy;
        const dist2 = dx*dx + dy*dy;
        if (dist2 > SCAN_RANGE * SCAN_RANGE) return;

        // относительные координаты в "секундах" (vel у пули — пиксели/тик, *60 -> px/s)
        rel.set(dx, dy);
        relV.set((b.vel.x - uvx) * tickRate, (b.vel.y - uvy) * tickRate);

        const vv = relV.dot(relV);
        if (vv < 0.0001) return;

        let t = -(rel.x * relV.x + rel.y * relV.y) / vv;
        if (t < 0) return;
        if (t > HORIZON) return;

        // точка ближайшего подхода относительно игрока
        cpa.set(rel.x + relV.x * t, rel.y + relV.y * t);
        const dCPA = cpa.len();
        if (dCPA > SAFE_RADIUS) return;

        // перпендикуляр к relV в сторону, противоположную cpa
        // (если cpa очень близко к 0 — берём произвольный перпендикуляр)
        if (dCPA < 0.5) {
            perp.set(-relV.y, relV.x).nor();
        } else {
            perp.set(cpa.x, cpa.y).nor(); // от точки сближения наружу
        }

        const w = (1.0 - dCPA / SAFE_RADIUS) * (1.0 - t / HORIZON);
        evade.add(perp.x * w, perp.y * w);
        totalWeight += w;
    });

    if (totalWeight <= 0) return;

    evade.scl(DODGE_BOOST / totalWeight);

    // подмешиваем к движению юнита: moveAt принимает желаемую скорость в px/tick
    // переводим обратно: px/s -> px/tick
    const speed = unit.type.speed; // максимум px/tick
    evade.limit(speed);
    // используем movePref если доступно (новый API), иначе vel.add
    if (typeof unit.movePref === "function") {
        unit.movePref(evade);
    } else {
        unit.vel.add(evade.x * 0.5, evade.y * 0.5);
    }
}

Events.run(Trigger.update, function(){
    try { tick(); } catch(e) { /* глушим, чтоб не спамить лог */ }
});

print("[dodger] loaded");
