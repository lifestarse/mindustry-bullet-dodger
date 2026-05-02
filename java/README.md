# Bullet Dodger — Mindustry mod (v146)

Авто-баит и уклонение от Duo/Salvo (включая silicon-homing).

## Сборка

Требуется JDK 17 и интернет (gradle тянет Mindustry/Arc с jitpack).

```bash
cd java
gradle jarAndroid          # или ./gradlew jarAndroid если есть wrapper
```

Артефакт: `dist/Dodger.jar`. Скопируй в `%AppData%\Mindustry\mods\` и перезапусти игру.

## Использование

В игре нажать `Ctrl+Shift+B` — toggle. В верхнем углу появится `[dodger] ON / OFF`.

Включённый мод:
- ищет в радиусе 600 px кластер из Duo/Salvo/Hail/Scorch/Wave;
- находит точку (pivot), накрытую максимумом таких турелей и не накрытую ни одной из death-list (Lancer, Arc, Cyclone, Foreshadow, Spectre, Scathe, Ripple, Meltdown, Fuse, Tsunami, и erekir-аналоги);
- выводит юнита на круговую орбиту радиуса 24 px вокруг pivot;
- параллельно на каждом тике уклоняется от приближающихся пуль (CPA с переключением на anti-PN при `homingPower > 0.15`).

## Тестовая схема

Песочница, флаг team = sharded:
1. Поставить вокруг 4× Salvo с silicon в патронах, на расстоянии ~25 тайлов друг от друга.
2. Спавнить Gamma на свою команду.
3. Включить мод — юнит должен залететь в общий радиус всех 4 Salvo и кружить.
4. Метрика: damage_taken за 60 секунд. Цель — < 30 HP.

## Параметры (для тюнинга в коде)

| Файл | Константа | Значение | Что меняет |
|------|-----------|---------|-----------|
| PivotPlanner | ORBIT_R    | 24f  | радиус орбиты, px |
| PivotPlanner | SCAN_R     | 600f | окно поиска турелей |
| PivotPlanner | HYSTERESIS | 1.15 | порог смены pivot |
| BulletDodger | HOMING_PN  | 0.15 | порог переключения на anti-PN |
| BulletDodger | HORIZON    | 60f  | прогноз CPA, тиков |
| BulletDodger | SAFE_R     | 24f  | зона тревоги, px |
