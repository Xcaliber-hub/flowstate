# Flowstate

A native Android focus app built with Kotlin, Jetpack Compose, Material 3, Hilt, Room and DataStore. This is the **Flowstate** project (`com.flowstate.focus`), not Stride.

## New in 1.1

- A Groups navigation tab, with email/password account creation, sign-in, password reset and sign-out.
- Private groups: create a room, join using a 12-character code, share an invite, switch groups and leave.
- A weekly leaderboard with bundled preset avatars or circular initials, rank, focus totals and a highlighted current-user row. Group weeks run Monday–Sunday in India Standard Time (IST, Asia/Kolkata), resetting at Monday 00:00 IST for every member.
- A Live tab inspired by the supplied references: focusing members in an avatar grid with elapsed timers, an invite tile, a separate “Not focusing” section and a shortcut to the existing focus timer. Stale presence is not presented as an ongoing live session.
- Editable profile names and a preset-avatar picker with Sprout, Sunbeam, Moon, Fox, Orbit, Summit, Cloud, Ember, Turtle, Jelly, Comet and Bloom. Avatar IDs are persisted in Firestore; artwork is bundled and initials remain available. No photo uploads or Firebase Storage.
- The supplied owl replaces the launcher and splash artwork. The pale outer border and corner decoration are removed. Adaptive icons use a separate full-bleed gray background and transparent owl artwork; legacy-density icons are also included.

## Open and build

Open this folder in Android Studio with JDK 17 or newer and an Android 35 SDK. Gradle 8.9 wrapper scripts and the wrapper JAR are included.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows, use `gradlew.bat`. Run on Android 8.0 / API 26 or newer. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` after a successful local build. For signed releases and in-place updates, follow [Update installation](UPDATE_INSTALLATION.md).

## Enable real groups

The supplied Flowstate Firebase client values are preserved unchanged. No Firebase project, account or backend deployment is created by this source update. The personal timer, tasks, statistics and settings remain local and work without cloud setup. The Groups page clearly explains when cloud configuration is missing; it does not show fake accounts or fake members.

Follow [Groups setup](firebase/GROUPS_SETUP.md): register `com.flowstate.focus` in your Flowstate Firebase project, enable Email/Password Authentication, create Firestore, fill `app/src/main/res/values/flowstate_firebase.xml`, and deploy the supplied Firestore rules. The app uses Firebase Authentication and Firestore only; no Storage bucket or Google Services Gradle plugin is needed. Cross-device sign-in and invites need internet access and the configured backend. Firebase service availability and quotas are managed in your own Firebase project.

See the setup guide for weekly-total accounting and device limitations. This is a focus-accountability app, not a tamper-proof competition; client-reported focus time is not independently verified by a server.

## Existing features preserved

Pomodoro, custom countdown and stopwatch timers; standalone breaks with a break-over notification; foreground-service chronometer notification; strict focus mode; Focus Guard; app blocking through the Accessibility Service; tasks with categories, priorities and deadlines; day/week/month/year statistics and charts; calendar heatmap, streaks and daily goals; theme, dynamic color, accent, Liquid UI and CSV export; floating navigation and tablet-friendly content width.

Enable app blocking in **Settings → Focus mode → App blocking service**, then grant the Accessibility permission. Notification permission is required on recent Android versions. Test background timers on real devices, especially manufacturers with aggressive battery restrictions.

## Before publishing

Configure release signing. Review the Accessibility Service disclosure and Play Console declaration, and the foreground-service `specialUse` justification. Test multi-user group operations against your own staging Firebase project before distributing a production build.

## Weekly leaderboard reset

Version **1.1.4** (Android version code **6**) adds hold-to-delete for past sessions, standalone breaks (5/10/15 min or custom) with a break-over notification, a group member profile sheet (weekly focus, league and rank, sessions, average), today's focus time in the Live tab, twelve more preset avatars (24 total), a 16-hour daily goal limit and a shared card depth effect. It hides Start focus while a focus block is running or paused and retires the Flow timer (old Flow sessions still show; a running Flow block resumes as a stopwatch). **Publish the 1.1.4 `firebase/firestore.rules`** so the new avatars and member stats are accepted; until then the app falls back to the 1.1.3 member payload and new-stat fields show "—".

Version **1.1.3** (Android version code **5**) moves the shared group leaderboard week to Monday–Sunday in India Standard Time (IST, Asia/Kolkata). The week and its countdown reset together at Monday 00:00 IST.

## Earlier avatar and visibility update

Version **1.1.2** (Android version code **4**) fixes avatar-save feedback, serializes avatar changes with background group publishing, and replaces old member snapshots to remove rule-incompatible legacy fields. The source already contained all twelve artwork mappings; this was not a missing-image issue. See [Update installation](UPDATE_INSTALLATION.md) to keep the existing signing key and local app data.

The included rules and Firebase CLI target are for project **`flowstate-241f0`**, database **`(default)`**. No live deployment was made. A new source build cannot make a six-avatar ruleset accept the new IDs; publish the twelve-avatar rules to the project actually used by the installed app. The fix reports that project's name and the denied write path.

## Accent-color picker restored

The six original accent presets remain Purple, Teal, Pink, Green, Blue and Orange. Settings now uses explicit 48dp color swatches in a wrapping layout, restoring the missing controls and keeping them reachable on narrow screens. Turn Dynamic color off to choose a preset on Android 12+; with Dynamic color on, wallpaper colors still take priority. Saved accent selection and light/dark contrast palettes are unchanged.

## In-app updates

Flowstate checks the latest GitHub Release on launch and shows an "Update available" pop-up when a newer version exists. Users tap **Update now**, the APK downloads inside the app, and Android's installer opens (the first time, Android asks them to allow installs from Flowstate).

To ship an update: bump `versionCode` and `versionName` in `app/build.gradle.kts` and push to `main`. The workflow builds the signed release APK and publishes a release tagged `v<versionName>` with the APK attached. Every release must be signed with the same keystore, or Android refuses to install it over the old one.

## Check-in

During a focus block, Flowstate asks "Still focusing?" after every 30 min, 1 hr, 1.5 hr, 2 hr or 3 hr of running focus time (1 hr by default), both in the app and as a notification with an **I'm here** button. If nobody answers within 2 minutes, the timer pauses itself, backdated to when the prompt appeared, so those minutes are never counted. Breaks get no check-ins, and a countdown shorter than the interval never sees one. It also pauses strict blocks, since being away isn't quitting. Turn it off in Settings under **Check-in**, where you also pick the interval.

## Side navigation (experimental)

Settings → Appearance → **Side navigation** swaps the floating bottom bar for a vertical glass rail on the right edge, a little below the middle of the screen. The selected tab sits in an accent circle with a dot under it, the rail slides away while you scroll down, and screens keep a lane free on the right so nothing hides behind it. Turn the switch off to get the original bottom bar back.

## Stacked leaderboard

The weekly leaderboard in Groups shows each member as an overlapping capsule, fading from deep navy (#06141B) at rank 1 to pale grey (#CCD0CF) at the bottom. Text switches between light and dark to stay readable on each shade, your own row gets an accent ring, and tapping any capsule opens that member's profile.
