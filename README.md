# Flowstate

A native Android focus app built with Kotlin, Jetpack Compose, Material 3, Hilt, Room and DataStore. This is the **Flowstate** project (`com.flowstate.focus`), not Stride.

## New in 1.1

- A Groups navigation tab, with email/password account creation, sign-in, password reset and sign-out.
- Private groups: create a room, join using a 12-character code, share an invite, switch groups and leave.
- A weekly leaderboard with bundled preset avatars or circular initials, rank, focus totals and a highlighted current-user row. Group weeks run Monday–Sunday in UTC so every member uses the same boundary.
- A Live tab inspired by the supplied references: focusing members in an avatar grid with elapsed timers, an invite tile, a separate “Not focusing” section and a shortcut to the existing focus timer. Stale presence is not presented as an ongoing live session.
- Editable profile names and a preset-avatar picker with Sprout, Sunbeam, Moon, Fox, Orbit and Summit. Avatar IDs are persisted in Firestore; artwork is bundled and initials remain available. No photo uploads or Firebase Storage.
- The supplied owl replaces the launcher and splash artwork. The pale outer border and corner decoration are removed. Adaptive icons use a separate full-bleed gray background and transparent owl artwork; legacy-density icons are also included.

## Open and build

Open this folder in Android Studio with JDK 17 or newer and an Android 35 SDK. Gradle 8.9 wrapper scripts and the wrapper JAR are included.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows, use `gradlew.bat`. Run on Android 8.0 / API 26 or newer. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` after a successful local build. Release signing must be configured separately before publishing.

## Enable real groups

The supplied Flowstate Firebase client values are preserved unchanged. No Firebase project, account or backend deployment is created by this source update. The personal timer, tasks, statistics and settings remain local and work without cloud setup. The Groups page clearly explains when cloud configuration is missing; it does not show fake accounts or fake members.

Follow [Groups setup](firebase/GROUPS_SETUP.md): register `com.flowstate.focus` in your Flowstate Firebase project, enable Email/Password Authentication, create Firestore, fill `app/src/main/res/values/flowstate_firebase.xml`, and deploy the supplied Firestore rules. The app uses Firebase Authentication and Firestore only; no Storage bucket or Google Services Gradle plugin is needed. Cross-device sign-in and invites need internet access and the configured backend. Firebase service availability and quotas are managed in your own Firebase project.

See the setup guide for weekly-total accounting and device limitations. This is a focus-accountability app, not a tamper-proof competition; client-reported focus time is not independently verified by a server.

## Existing features preserved

Pomodoro, custom countdown, stopwatch and Flow timers; foreground-service chronometer notification; strict focus mode; Focus Guard; app blocking through the Accessibility Service; tasks with categories, priorities and deadlines; day/week/month/year statistics and charts; calendar heatmap, streaks and daily goals; theme, dynamic color, accent, Liquid UI and CSV export; floating navigation and tablet-friendly content width.

Enable app blocking in **Settings → Focus mode → App blocking service**, then grant the Accessibility permission. Notification permission is required on recent Android versions. Test background timers on real devices, especially manufacturers with aggressive battery restrictions.

## Before publishing

Configure release signing. Review the Accessibility Service disclosure and Play Console declaration, and the foreground-service `specialUse` justification. Test multi-user group operations against your own staging Firebase project before distributing a production build.

## Avatar and visibility update

See [Avatar changes](AVATAR_CHANGES.md) and [Overall visibility changes](OVERALL_VISIBILITY_CHANGES.md). The separate six-concept preview is review-only and is not bundled in the app. Deploy the updated Firestore rules to your intended project before using avatar saving.
