---
description: Hunt for bugs in existing code. Read-only Opus reviews by area with the Android bug checklist, then one fix ticket per bug you pick, each starting from a test that reproduces it
argument-hint: [scope: path, module or feature | blank = the riskiest areas from the codebase map]
---

Find bugs in code that already exists. For a diff you're about to push, `/code-review` is quicker; for a security pass, `/claude-security`. Scope: $ARGUMENTS

1. **Pick up to 4 areas.** From the scope, or when it's blank, from `.claude/.codebase-info/`: the riskiest parts first (persistence and migrations, concurrency and background work, state restoration and navigation, anything touching money, auth or personal data). Don't read source to pick them. One area is a module, package or feature folder that one reviewer can cover.

2. **File one read-only `review-audit` ticket per area** (Sidequest rules apply):
   - title `Bug hunt: <area>`, `--readonly true`, no declared files;
   - description: the area's paths; the absolute path of `$CLAUDE_PROJECT_DIR/.claude/kit/android-bug-checklist.md`; "Hunt for real bugs in this area with that checklist and report in its format as your closing body. Run no Gradle task and edit nothing."
   - They don't run Gradle, so they can all go in one wave.

3. **Dispatch and wait.** Don't hunt yourself in the meantime.

4. **Triage.** Merge the reports and drop duplicates. For each finding, read the cited lines yourself; keep it only if the scenario holds. Show the user a numbered list (severity, one line, `file:line`) and let them pick what gets fixed. The rest goes on the board only if they say so.

5. **One fix ticket per picked bug.**
   - Category by what the fix touches: UI → `interaction-design-implementation` (or `ui.tweak`); clear cause → `coding.normal`; unclear cause → `debugging`.
   - Declare its files. Description: the finding, then "First add a test that reproduces this and show it failing in your submit report; then fix it until the test passes."
   - Verify: the module's unit test task. A bug that only shows on a device says so in the description, and gets checked on a device after integration, through the route in CLAUDE.md → Devices.
   - Mark a crash or data-loss fix `--high-stakes`, so it gets a review before integration.

6. **Record:** a "Bug hunt" row in PROGRESS.md's audit table (date, areas, found / fixed). If a bug reveals a convention worth keeping, add it as a live-rule with `add-rule`.

Report any visual or device validation that couldn't run.
