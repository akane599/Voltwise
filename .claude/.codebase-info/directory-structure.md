# Directory Structure

*Last Updated: 2026-10-09*

The app source is organized by feature under `battery/`, plus layer packages (`ui/`, `viewmodel/`,
`settings/`, `di/`). See [modules.md](modules.md) for each package.

```
BatStatsAKA/
├── app/
│   ├── build.gradle.kts            # the only module: variants, signing, splits, screenshot suite, deps
│   ├── proguard-rules.pro
│   ├── schemas/com.akane.voltwise.battery.data.db.BatteryDatabase/{4,5,6,7,8,9}.json   # Room exported schemas
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/akane/voltwise/
│       │   │   ├── battery/        # BatteryApp, BatteryMainActivity + feature packages:
│       │   │   │   ├── actions/  apps/  data/{db,sampling}/  diagnostics/  drain/  measurement/
│       │   │   │   ├── insights/{engine/{detectors,eligibility,recommend,stats},actions,model}/
│       │   │   │   └── service/  shizuku/  tile/  util/  widget/
│       │   │   ├── data/Constants.kt
│       │   │   ├── di/AppModules.kt
│       │   │   ├── settings/
│       │   │   ├── ui/{NavGraph.kt, TestTags.kt, components/{chart/}, format/, navigation/, screens/{now/,insights/}, theme/}
│       │   │   └── viewmodel/
│       │   ├── res/                # values{,-es,-tr}/strings_<screen>.xml, layout/ (widgets, notification),
│       │   │                       # xml/ (widget providers, backup rules, locales, filepaths), drawable/, font/, mipmap-*/
│       │   └── assets/licenses/    # Space Grotesk OFL
│       ├── test/java/com/akane/voltwise/            # JVM unit tests (mirrors main) + support/EnglishStrings.kt
│       ├── androidTest/java/com/akane/voltwise/     # device tests; test/DeviceEnvironment.kt (RequiresShizuku)
│       ├── screenshotTest/kotlin/com/akane/voltwise/ui/        # @PreviewTest previews
│       └── screenshotTestDefaultDebug/reference/…        # committed screenshot PNGs (~203)
├── gradle/libs.versions.toml       # version catalog
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── scripts/                        # host checks (resources, migrations, DAO SQL) and device-test helpers
├── docs/                           # MEASUREMENTS, PLATFORM_NOTES, BUILD_AND_INSTALL, LOCALIZATION, VALIDATION, audits
├── fastlane/metadata/android/en-US/            # store listing + changelogs
├── .github/workflows/              # CI (test_if_it_builds → build-apk), releases, dependabot
├── .claude/                        # agent tooling: live-rules, kit, skills, agents, hooks, scripts, this map
├── CLAUDE.md, PROGRESS.md          # agent instructions; progress + decision log
├── README.md, CHANGELOG.md, AUDIT_REPORT.md, LICENSE
├── android-kit/                    # Claude Code installer (setup.sh: local-scope plugins, hooks, routing); see docs/CLAUDE_SETUP.md
└── code-audit-claude/              # /code-audit reviewer agents installer (install.mjs); not product code
```

Generated or ignored: `build/`, `app/build/`, `.gradle/`, `local.properties` (never read or edit it).
