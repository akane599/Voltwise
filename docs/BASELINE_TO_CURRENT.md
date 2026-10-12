# From BatStats 6.2.6 to the current development snapshot

> **Historical checkpoint (marked superseded on 2026-10-07). Current guidance: [README](../README.md).**

This is an ordered implementation checklist for reproducing the changes beyond the original app. The baseline is source revision `76bc831328572c81717b97ffb0e280b10b14b8ad` and the supplied 6.2.6 APK. The development version is 6.2.7-dev.

The original dashboard, advanced statistics, history, notification, and widgets already existed. Much of this work repairs their calculations and reliability; the entire app should not be described as newly added functionality.

Two checkpoints must remain distinct:

- The saved Debug and Preview APKs were built from `e52daf9`, the last completed build-and-test checkpoint. They report version code 737.
- The imported source also contains stage6l notification, reset-dialog, and test-synchronization changes. Their validation was interrupted. They are absent from the saved APKs and have no completed validation result.

On 2026-09-27 this snapshot was prepared for the newly created `akane599/BatStatsAKA` repository on `codex/android16-reliability`. Its parent is the original baseline. The original development commits and their CI/PR references are historical evidence from the previous environment; this import does not recreate that missing commit series or establish a new passing CI run.

## Ordered implementation checklist

1. **Repair the build and target Android 16.** Use minimum API 26, target API 36, compile SDK 37 and JDK 21. The supplied APK targeted API 37; the development target is intentionally API 36. Replace the incompatible alpha Compose combination with a stable BOM, then enforce it to prevent the settings dependency from pulling in an incompatible Material3 alpha that crashed text fields. Separate APK distribution copies from AGP outputs. Correct the CI SDK package name and constrain workers/memory after actual setup and out-of-memory failures.

2. **Repair the monitoring service lifecycle.** Replace indefinite `dataSync` monitoring with the declared `specialUse` foreground service. Protect asynchronous boot handling with `goAsync()`, bound settings loading, finish the receiver, and prompt manual startup when automatic startup fails. Make start/stop repeatable, cancel service work on stop, and prevent startup during history deletion. Samsung background behavior still needs hardware validation.

3. **Rework privileged access.** Prefer Shizuku while preserving ordinary, Root and ADB modes. Distinguish availability from authorization; react to helper/server loss and reconnection. Select one backend per read and report failure rather than silently substituting another source. Correct ADB permission/AppOps checks. Add request IDs, bounded cancellable commands, command validation and framed responses. Reject failed, timed-out or truncated output. Run Root/kernel reads through `su`.

4. **Validate ordinary readings.** Keep unsupported values unavailable and legitimate zero values intact. Validate percentage, current, charge, energy, voltage and temperature; normalize units; keep instantaneous and average current separate. Derive power only from valid inputs. Preserve fractional estimates and reject malformed/nonfinite values. Honor explicit discharging status even with a cable connected. Flag sign conflicts without guessing vendor scaling or reversing current.

5. **Replace drain calculations with observed intervals.** Begin totals at monitoring start/reset. Use monotonic durations, separate charging from discharge, and assign screen-state intervals using actual boundaries. Compute charge from valid counter differences and show covered duration. Exclude gaps and incompatible counters; label voltage-derived energy as estimated. Share totals and reporting endpoints between dashboard, details and notification.

6. **Separate screen-off, CPU suspend and Doze.** Remove the since-boot elapsed-minus-uptime test that mislabeled later screen-off snapshots as deep sleep. Calculate suspend over observed intervals and track Doze separately. Include noninteractive AOD in screen-off without treating it as proof of CPU sleep. Read elapsed/uptime adjacently after battery-property calls to avoid introducing IPC latency into suspend accounting.

7. **Handle ordering and recovery.** Retain one actual capture for up to two seconds when a poll sees a state transition before its matching broadcast. Missing/conflicting events remain gaps; this adds no timer or wakeup. Separate battery, storage and event-subscription failures, keep polling failures recoverable, and handle database-operation cancellation without killing the repository owner. Preserve stop/reset during SQL failures. Prevent delayed SQL publication from overwriting newer live readings, including captures within the same millisecond.

8. **Replace unsupported ETA and health calculations.** Remove the assumed 4,000 mAh capacity. Require a stable counter-based discharge trend with at least five readings over ten minutes, adequate movement and comparable rates across the window. Reset the trend after gaps/transitions. Label Android charging ETA as estimated. Remove cycle-count-derived health percentages; supported full-charge/design-capacity ratios remain fuel-gauge estimates.

9. **Finish automatic sessions and migrate storage safely.** Implement charging/discharging/plugged/unknown sessions through a single writer. Enforce one active session. Store source, observation identity, coverage, sample links and closure reasons. Handle restarts/interruption without inventing continuity. Add nondestructive schema 1/2/3-to-4 migration and legacy evidence labels. Keep missing averages unavailable rather than converting invalid arithmetic to zero.

10. **Correct advanced statistics.** Follow Android 16 producer units and field ordering, including job/sync counts and durations and Doze labels. Use current non-consuming `dumpsys batterystats --proto --charged` on supported Android 9+ producers. Keep Android's cumulative reporting window distinct from local sessions. Clear stale/failed subreads instead of refreshing their timestamp. Remove the duplicate writer that attributed earlier usage to newly seen UIDs and the arbitrary foreground-app 80/20 mA heuristic. Retain Android UID estimates with shared-UID, reporting-window and attribution limits.

11. **Make history operations bounded and transactional.** Validate complete imports before committing, skip identical records, reject conflicts, and never resume imported sessions. Bound files to 64 MiB and storage to 100,000 samples/10,000 sessions. Export units, UTC timestamps, sources and periods; date filters select samples and overlapping sessions without misrepresenting full session totals. Serialize clearing with monitoring shutdown. Apply retention and make automatic backup settings-only; history export remains deliberate.

12. **Repair history navigation and charts.** Fix the session card click handler, add incremental browsing/filtering/search beyond the first 100 records, and make details/export scrollable. Use bounded session-linked charts with units/time labels, explicit gaps and source evidence. Avoid mixing imported/legacy readings into local charts and clear stale details after deletion.

13. **Make alerts effective.** Implement low/high level, temperature, sustained high discharge and full-charge episodes while monitoring is active. Add hysteresis and persistent latches to prevent repeated alerts after each sample/restart. Require three high-current readings over at least one minute. Use Android FULL status for completion. Recover latches after delivery failure; use Android notification settings for sound/vibration/permission. Retire ineffective UI switches while preserving saved keys.

14. **Preserve the rich notification.** Share observed screen-on/off charge/rates/durations, charging, CPU suspend, Doze, source and freshness. Keep identity stable and updates quiet, avoid a continually reset timestamp, and retain navigation/reset actions. Normally cap updates to a 30-second cadence with important state changes sooner. Recover after display IPC failures. The final unverified source change shortens error labels, puts the window first, and explicitly represents missing/partial coverage to reduce clipping.

15. **Improve the three existing widgets.** Add missing-value, capture-time and stopped labels, Celsius/Fahrenheit, accessibility descriptions and stored ETA handling. Hide active ETA after monitoring stops. Skip delivery when no widgets are installed. Use service/explicit refreshes without adding periodic wakeups. The final unverified screenshot barrier waits for Android UI idle.

16. **Improve UI/settings/localization.** Reduce crowded dashboard actions, use current-window responsive layouts and scrollable recovery/detail states, and correct contrast. Make Compose resources configuration-aware and settings labels/options resource-backed. Validate settings-import structure, size and ranges, with inline feedback. Consolidate the baseline's 17 English-duplicate locale files into fallback and add complete Spanish/Turkish translations; the latest recorded resource check covers 496 keys each. The final unverified reset-dialog change stacks actions to address overlap at 200% font.

17. **Add sources and diagnostics.** Explain sources, units, freshness, coverage and failures in the app. Keep up to 60 grouped local diagnostic events within 32 KiB, using bounded/coalesced persistence. Share reports only deliberately, using fixed diagnostic codes rather than raw privileged dumps or activity identifiers. Add measurement, platform, localization, validation and installation guides.

18. **Bound monitoring overhead.** Default ordinary sampling to 30 seconds and advanced reads to five minutes. Remove duplicate collectors, bound output/storage/chart work, coalesce diagnostic writes, and remove unused dependencies/permissions. No physical battery-saving percentage or measured energy-overhead improvement is established.

19. **Prepare development delivery.** Add separate Debug and optimized nondebuggable Preview packages, checksums, certificate/revision metadata and offline guides. Add a manual APK workflow reused by push/PR CI, ordinary and real Shizuku phases, retained reports, and a prepared 16 KiB emulator phase. Keep normal CI read-only and separate from stable signing secrets. Correct release IDs and require explicit publication, with publication/version-bump defaults disabled. The manual Run workflow control requires the workflow on the default branch.

20. **Add regression and device coverage.** Cover readings, intervals, parsers, transports, history, alerts, diagnostics, settings and formatting in JVM tests. Add Android migrations/recovery/history/navigation/notification/widget/native/Shizuku checks. Supply a prebuilt-APK runner for constrained hosts, rejecting incomplete boot, crashes, missing results, zero tests and skips. Repair test signatures, receiver interception, permission selection, activity cleanup and screenshot-byte transport. These harness fixes do not replace real device assertions.

## Last completed validation, not a new run

| Check at the recorded e52daf9 checkpoint | Result |
| --- | --- |
| JVM suites | 105 cases passed in each Debug/Preview variant |
| Full lint | Zero errors; 201 Debug and 200 Preview warnings |
| Assembly | Debug, optimized Preview and Android-test APKs built |
| Ordinary API 36 methods | 27/28 passed in each of two recorded hosted runs |
| Real Shizuku integration | 1/1 passed in both runs, including authorization, helper/server recovery and ordinary-data survival |
| Storage recovery, lifecycle/notification, widgets | Passed at the latest recorded checkpoint |
| Native loading on 4 KiB pages | Passed |
| Screenshots | 62 nonempty PNGs; visual review incomplete |
| Stage6l final changes | Validation interrupted; no completed build/test result |
| Physical Samsung accuracy or energy savings | No supporting measurements |

## Remaining to-do list

- Complete compilation/tests for the final reset-dialog, notification and synchronization changes; then rebuild installable APKs from the final source.
- Resolve the last ordinary-test failure: reset-dialog Cancel followed by system Back did not reach the dashboard. The synchronization change has not established whether the cause is fully resolved.
- Verify non-overlapping reset buttons at 200% font and readable expanded notification content. Both issues were confirmed in the older tested checkpoint; source fixes remain unverified.
- Finish landscape, dark-theme and settled-frame visual review. Landscape was not reached after the navigation failure; some PNGs showed a previous frame.
- Execute 16 KiB runtime checks. ZIP/native LOAD alignment passed, but the stricter graphics-path RELRO-end warning remains an unresolved runtime question.
- Validate remaining ADB/boot behavior and Root/vendor paths where suitable hardware exists. Samsung current polarity/scaling, capacity calibration, AOD, background restrictions and actual energy overhead remain unverified.
- Review remaining lint warnings and stale historical documentation/artifact status notes. Native-speaker translation review is also outstanding.
- If dependency automation is wanted, address the inherited Dependabot permission mismatch separately; no repository privileges were changed for this import.

Shared-UID energy cannot be reliably split into individual apps. Android estimates are not independent electrical measurements. Unsupported vendor readings remain unavailable. Those are capability limits, not evidence that zero consumption was measured.

The saved packages are `org.mlm.batstats.debug` and `org.mlm.batstats.preview`. They install alongside the original `org.mlm.batstats` and cannot update it without its signing key. Preview updates require a compatible certificate; ephemeral CI keys may differ between runs. APKs/build caches/keys are excluded from the Git snapshot.

See [AUDIT_REPORT.md](../AUDIT_REPORT.md), [PROGRESS.md](../PROGRESS.md), [VALIDATION.md](VALIDATION.md), [MEASUREMENTS.md](MEASUREMENTS.md) and [BUILD_AND_INSTALL.md](BUILD_AND_INSTALL.md) for the retained evidence and commands.
