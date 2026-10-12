---
description: Statistical detector changes carry false-positive and power evidence
priority: 5
globs: ["app/src/main/java/com/akane/voltwise/battery/insights/engine/**"]
prompt: ["detector", "false positive", "false-positive", "trend", "baseline", "Theil", "Mann-Kendall"]
---
- A change to a statistical detector's firing rule ships with seeded JVM tests of its false-positive rate on flat, noisy and serially correlated data, with the bound stated in the test.
- Keep positive controls for a realistic effect size, so a stricter rule can't silently stop detecting anything.
- Report counts out of N (e.g. 5/500), not "passes".
- Never pick seeds or loosen bounds to make a test pass; if the bound fails, change the rule.
- Also run any candidate rule on held-out seeds: the same generators with seeds offset by a constant fixed before measuring. Report those counts next to the asserted ones. A rule that passes only the asserted seeds is fitted, not fixed.
- Generate test series in the metric's production domain, and check the parser and aggregation before assuming a bound. Background-time and partial-wakelock shares are sums of timers and exceed 1.0 (`SessionSnapshotStore.kt` `collapseByUid`, `AppWindows.kt`).
- A change that can move the notifying severity (HIGH) ships a seeded severity control, e.g. a sustained 4× rise reaches HIGH in ≥ 450/500. Firing-only controls missed a halving of HIGH (SQ-380).
