---
description: Independent audit of an implementation plan before you approve it, as a read-only plan-review.frontier ticket (GPT-6 Astra, the other model family) with the Android checklist
argument-hint: [plan file path | blank = newest plan in ~/.claude/plans or the plan in this conversation]
---

Audit the plan before it becomes tickets. Target: $ARGUMENTS

1. **Resolve the plan:** the given path → else the newest `~/.claude/plans/*.md` → else the plan most recently presented in this conversation. In that last case, write it to `~/.claude/plans/<slug>.md` first. Use absolute paths from here on; executors run in worktrees outside this checkout.

2. **File one read-only review ticket** (Sidequest skill rules apply: classify, then dispatch):
   - title `Plan audit: <plan name>`, category `plan-review.frontier` (android-kit profile: GPT-6 Astra, Opus when the gateway isn't live; if the board doesn't have that category, use `review-audit`), `--readonly true`, no declared files;
   - description: the absolute plan path; the absolute path of `$CLAUDE_PROJECT_DIR/.claude/kit/android-plan-checklist.md`; "Audit the plan with that checklist against this repository. Report in the checklist's report format as your closing body. Do not edit files."
   - If the plan touches data migrations, security, release/signing or more than ~10 files, add `--high-stakes` and file a **second** identical ticket in category `review-audit` (Claude Opus). Same input, neither sees the other's report. `sidequest models` shows where each resolved; if both are on Claude (gateway not live), say so in the verdict.

3. **Dispatch and wait.** Don't audit it yourself in the meantime; you wrote the plan and would defend it.

4. **Merge and apply.** Read the closing report(s) (`sidequest comments <ref>`). With two reports:
   - flagged by both → keep;
   - flagged by one → check it in the repo yourself and keep it only if it holds;
   - different verdicts → take the stricter and say in one line why.

   Apply Blockers and Fixes to the plan file (plan edits, not code), put `audited: <date> (<refs>)` at the top, and add one line to PROGRESS.md's decision log and audit table.

5. **Tell the user** the verdict in ≤5 lines. Once they approve, pin the plan as a Sidequest story (see the `user-story` skill) and file the ticket waves.
