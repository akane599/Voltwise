# Voltwise Codebase Map

*Last Updated: 2026-10-09*

Voltwise (formerly BatStats; GitHub repo `akane599/Voltwise`, formerly BatStatsAKA; local checkout folder still `BatStatsAKA/`) is an Android battery monitor: live readings, observed charge/discharge sessions saved in
Room, and per-app battery use via a privileged `dumpsys batterystats` (Shizuku / root / ADB `DUMP`).
It is one Kotlin module (`:app`, namespace `com.akane.voltwise`, appId `com.akane.voltwise`) built with Compose
Material 3 (dark-only), MVVM + Koin, Navigation 3 and coroutines/Flow. A foreground service drives the
single sampler (`SamplingController`) and the single history writer (`BatteryRepository`).

| Doc | Covers |
| --- | --- |
| [architecture.md](architecture.md) | Layers, key flows (monitoring, live readings, per-app stats, insights, deep links), invariants |
| [entry-points.md](entry-points.md) | Application, activity, FGS, boot receiver, tile, widgets, notification actions, navigation routes |
| [modules.md](modules.md) | Every package with its key files; screen → ViewModel → repository table |
| [database.md](database.md) | Room v9 tables, DAOs, migrations (4→5 irreversible; 5→6, 6→7 and 7→8 additive; 8→9 data-only, forward-only: retire legacy capture certification, snapshots and waker attribution, resolve ACTIVE findings), other persistence, checks |
| [insights.md](insights.md) | Insights: findings engine, detectors, privileged actions + journal, notifier, UI, wiring |
| [privileged-shell.md](privileged-shell.md) | ShellRunner modes, Shizuku bridge/user service, batterystats parsing, session snapshots |
| [tech-landscape.md](tech-landscape.md) | Toolchain versions, libraries, variants/signing, CI, localization |
| [directory-structure.md](directory-structure.md) | Annotated tree |
| [patterns.md](patterns.md) | VM/repository pattern, concurrency seams, error handling, UI conventions, testing, style |
| [onboarding.md](onboarding.md) | Environment, commands, common change recipes |

## How to use this map

- New here? Read `onboarding.md` then `architecture.md`.
- Before touching code, skim the doc(s) for the area you're changing.
- These docs hold concrete file paths. Use them to go straight to the relevant code.

## Keeping this map current

After a change that affects architecture, directory structure, dependencies, the data model, entry
points, APIs/events, or conventions, refresh the affected docs with the `update-codebase-map` skill
(`/codebase-mapper:update-codebase-map`). Small, internal-only changes don't need an update.
