# Bullet Dodger — Mindustry mod

Auto-bait & dodge bot for Mindustry v154. Drone enters turret radius to soak ammo while sampling-based evasion (720 directions, CPA scoring) keeps it alive.

## Features

- **PivotPlanner** — finds where to stand: max-coverage of safe annuli around baitable turrets (Duo/Salvo/Hail/Scorch/Wave), avoids death-disks of dangerous turrets (Lancer/Arc/Cyclone/Foreshadow/Spectre/Scathe/Ripple/Meltdown). Aggressive scoring rewards close engagement.
- **BulletDodger** — every tick samples 720 directions, computes CPA collision for every enemy bullet, picks direction with minimum expected hits + bias toward pivot.
- **Lissajous drift** — pivot wanders within a 40 px region so turrets can't saturate one spot.
- **GoTo controller** — returns to drifting pivot when no immediate threat.

## Build

Requires JDK 17. Mindustry/Arc jars are not committed (see `.gitignore`); fetch them once:

```bash
mkdir -p java/libs
curl -L -o java/libs/mindustry-core.jar 'https://jitpack.io/com/github/Anuken/Mindustry/core/v146/core-v146.jar'
curl -L -o java/libs/arc-core.jar       'https://jitpack.io/com/github/Anuken/Arc/arc-core/v154/arc-core-v154.jar'

cd java
javac -encoding UTF-8 -cp "libs/mindustry-core.jar;libs/arc-core.jar" -d build/classes src/main/java/dodger/*.java
cp mod.hjson build/classes/
jar cf dist/Dodger.jar -C build/classes .
```

Copy `dist/Dodger.jar` to your Mindustry mods folder:
- Steam: `C:\Program Files (x86)\Steam\steamapps\common\Mindustry\saves\mods\`
- Standalone: `%AppData%\Mindustry\mods\`

## Usage

In-game:
- Settings → Dodger → enable
- or hotkey `Ctrl+Shift+B`

Diagnostics: `last_log.txt` gets a `[dodger] ...` status line every second with pivot/threats/danger metrics.

Visual overlay:
- white cross = ideal pivot
- red circle = current target (drifted pivot)
- yellow line from unit = evade vector
- green line from unit = goto vector

## License

(none yet)
