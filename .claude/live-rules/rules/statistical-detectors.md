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
