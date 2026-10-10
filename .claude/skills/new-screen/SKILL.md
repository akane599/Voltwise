---
name: new-screen
description: Scaffold a new BatStats Compose screen the way the existing screens are built — Koin wrapper + stateless XxxContent, Navigation 3 route in ui/NavGraph.kt, strings in all locales, optional ViewModel + unit test, and a @ScreenPreviews screenshot test with reference images. Use when the user asks to add a screen/page/destination.
argument-hint: "<ScreenName> — <one-line purpose>"
---

# New BatStats screen

Arguments: `$ARGUMENTS` (e.g. `Alarms — configured battery alarms`). Derive `Xxx` (PascalCase, no "Screen" suffix). Code shapes are in [template.md](template.md) — copy them, don't improvise a different structure.

## Rules (from `.claude/live-rules/rules/android-code.md`, enforced by review)
- Wrapper `XxxScreen` holds everything stateful: `koinViewModel`/`koinInject`, `collectAsStateWithLifecycle`, effects, launchers, Intents, clipboard, system services, root/Shizuku. `XxxContent` is stateless: plain state + lambdas, `modifier: Modifier = Modifier` first optional param, applied to the root `Scaffold`.
- No literal user-facing strings, no `!!`, trailing commas, `val` by default. Don't add `Color(0x…)`; use `MaterialTheme`.
- Keep other screens' public signatures stable; if a caller needs an `onOpenXxx` callback, update its wrapper, its `XxxContent`, and its screenshot test together.

## Steps
1. **State.** If the screen reads data, add `app/src/main/java/com/akane/voltwise/viewmodel/XxxViewModel.kt` with an `@Immutable data class XxxUiState` and expose it as `StateFlow<XxxUiState>` named `state`, register it in `app/src/main/java/com/akane/voltwise/di/AppModules.kt` next to the others (`viewModel { XxxViewModel(get()) }`), and add `app/src/test/java/com/akane/voltwise/viewmodel/XxxViewModelTest.kt` (JUnit4 + kotlinx-coroutines-test, hand-written fakes — the project has no mocking library).
2. **Create the screen.** Create `app/src/main/java/com/akane/voltwise/ui/screens/XxxScreen.kt` from the template. If the wrapper combines several flows instead, build an `@Immutable data class XxxUiState` declared in the screen file.
3. **Navigation.** In `app/src/main/java/com/akane/voltwise/ui/NavGraph.kt`, add a `Routes` entry (a `@Serializable data object Xxx : Routes` or a `@Serializable data class` for arguments) in `app/src/main/java/com/akane/voltwise/ui/navigation/Routes.kt`, then add `entry<Routes.Xxx> { XxxScreen(onBack = popBack) }` to the `entryProvider`. Navigate from the caller with `topLevelBackStack.navigate(Routes.Xxx)`; use `openRoot(Routes.Tab)` or `select(Routes.Tab)` when the action targets a top-level tab.
4. **Strings.** Add every new key to `app/src/main/res/values/strings.xml` **and** `values-es/` **and** `values-tr/` — `scripts/check_resources.py` fails on missing translations or mismatched format args. Flag machine-made translations in your report.
5. **Screenshot test.** Create `app/src/screenshotTest/kotlin/com/akane/voltwise/ui/screens/XxxScreenshotTest.kt` from the template: `@PreviewTest @ScreenPreviews` populated state, `@PhonePreview` OLED, plus `@PhonePreview`/`@TallPhonePreview` for states that look very different (empty, loading, error). Dates only from `FIXED_TIME_MS`; no `System.currentTimeMillis()`/`Random`; preview names without ".".
6. **Build + screenshot references.**
   - `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain -q`
   - `python3 scripts/check_resources.py`
   - Validate screenshots with `:app:testDebugScreenshotTestDefaultTestSuite` (or `bash .claude/scripts/run_screenshot_tests.sh`). Only in a UI ticket, update references through `bash .claude/skills/screenshot-rebaseline/rebaseline.sh` after following its triage workflow. Never update references as routine scaffold verification.
7. **Verify:** dispatch the `compose-reviewer` agent on the new files and a fresh agent to run build + unit + `:app:testDebugScreenshotTestDefaultTestSuite` and open the screen on the emulator. Update `PROGRESS.md`.
