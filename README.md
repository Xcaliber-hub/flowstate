# Flowstate

A native Android focus app: Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore.
This is **phase 1**: the reliable core. It has not been compiled or run on a device yet (see "First run").

## First run

1. Open the `Flowstate` folder in **Android Studio** (Ladybug or newer, JDK 17). Let Gradle sync.
2. Run on a device or emulator (Android 8.0+, API 26).
3. In the app: Settings > Focus mode > App blocking service, and allow it in Accessibility settings.
4. Test on at least one Xiaomi or Samsung phone. They are the most aggressive about killing background work.

Versions are pinned in `gradle/libs.versions.toml` to a known-compatible set (AGP 8.7.3, Kotlin 2.0.21,
Compose BOM 2024.12.01). If sync reports a mismatch, adjust that file first.

## What is in phase 1

- Timer modes: Pomodoro, Countdown (custom length), Stopwatch, Flow (earns a break of one fifth of the focus time)
- Foreground-service timer with a system-chronometer notification, break alerts, screen-on option
- Strict mode: no pause, no quit, hold-to-quit emergency exit, no notification actions
- Focus Guard: a nudge when you leave for another app
- App blocking by package name (AccessibilityService), with Social and Games quick-add
- Tasks: categories, priority, deadlines, animated completion
- Statistics: day/week/month/year, bar and line charts (Canvas), calendar heatmap, streak,
  average session, completion rate, productivity score, focus by subject
- Settings: theme, Material You dynamic color, accent colors, Liquid UI toggle, timer defaults,
  daily goal, CSV export
- Floating pill navigation that hides on scroll-down, with haptics
- Tablet/foldable-friendly width cap, edge-to-edge, AMOLED dark theme

## Why it should glitch less

- The timer stores absolute timestamps, never a ticking counter, and persists every change.
- One coroutine sleeps until the phase ends; the notification countdown is drawn by the system.
- A process that was dead for longer than a phase closes the session instead of replaying it.
- Blocking uses package names, which do not break when other apps redesign their screens.
- Progress ring and chart animations read state in the draw phase, so they do not recompose.

## Not in phase 1

Focus music and white noise (needs audio assets), Reels/Shorts blocking, YouTube/Browser study modes,
website blocking, app time limits, recurring focus schedules, subtasks, recurring tasks, drag and drop,
widgets, Quick Settings tile, lock-screen timer, notification blocking, Baseline Profile generation
(needs a macrobenchmark module), multiplayer rooms, cloud sync, Wear OS.

True backdrop blur is intentionally left out: translucent glass surfaces cost almost nothing, while
real blur is the usual cause of dropped frames on mid-range phones. The `Haze` library is the upgrade path.

## Before publishing to Google Play

- The Accessibility Service needs a prominent in-app disclosure (included) and a Play Console declaration.
- Foreground service type `specialUse` needs a justification in the Play Console form.
- Replace the placeholder icon in `res/drawable/ic_launcher_foreground.xml`.
- Add a release signing config.
