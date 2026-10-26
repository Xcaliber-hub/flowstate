# Flowstate 1.1.4 verification

## Checks run for 1.1.4

Checked on an ARM64 Linux sandbox with Gradle 8.9, Android SDK platform 35, build-tools 34.0.0, JDK 17 (Temurin) and an ARM64 `aapt2` 2.19 (passed via `-Pandroid.aapt2FromMavenOverride`, because the Maven `aapt2` is x86-64 only):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- Kotlin compilation passed with **no compiler warnings**; a debug APK was assembled (version **1.1.4**, code **6**).
- All **16 JVM unit tests** passed (10 existing, updated for 24 avatars, plus new tests for the retired Flow mode fallback, break state, IST weekly session count, IST daily totals and leagues).
- Android lint: **0 errors**, 59 warnings, 1 informational (1.1.3 reported 61 warnings).
- The Firestore emulator suite (`firebase/tests`) was extended for 24 avatars, the optional member stats and live timer validation, but **was not run**: the sandbox could not download the Firestore emulator (HTTP 403). Run `cd firebase/tests && npm install && npm test` (needs JDK 21+) before publishing the rules.

No device or emulator UI check was performed. Recommended manual checks: hold-to-delete on a today's session, Take a break → break-over notification with the app backgrounded, hidden Start focus while focusing or paused (Focus screen and Groups Live tab), the member profile sheet, and the light-mode card depth.

# Flowstate 1.1.3 verification (previous release)

## Checks passed

The final source was checked using Gradle 8.9, Android SDK 35 and JDK 21:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug compilation and APK packaging passed. The generated package metadata confirms version **1.1.3**, code **5**, package `com.flowstate.focus`.

All **10 JVM unit tests** passed, covering avatar catalog/validation/fallback, timer state and IST weekly calculations. Android lint reported **zero errors**, 61 warnings and 1 informational findings; this is not a zero-warning claim.

All **16 Firestore emulator tests** passed. They cover all twelve avatar IDs and initials reset in private profiles and member documents; original-to-new migration; legacy documents; multi-group publishing/readback and fresh-session restoration; stale-field merge denial followed by canonical replacement; old-six-rules rejection of all six new IDs; malformed payload rejection; and owner/membership access restrictions. Expected PERMISSION_DENIED messages appear during negative tests.

```sh
cd firebase/tests
npm install
npm test
```

Emulator tests use only `demo-flowstate` and `demo-flowstate-six-avatar`. No production Firebase project or account was accessed, and no rules were deployed. These tests exercise rule/payload compatibility, not the Android repository against a live server.

## Preserved

All original PNG files and Firebase client configuration were compared byte-for-byte with the supplied ZIP and are unchanged. Package identity and persistent release-signing configuration are preserved. Personal focus data, theme/accent settings, original six presets, six new presets and initials remain supported. No Firebase Storage, remote-image service or upload flow was added.

## Still requires a device/live check

No physical-device visual check, app upgrade, offline-save behavior, multi-device synchronization or production-rules verification was performed. The serialization and editor feedback changes compiled and passed lint; this is not a claim of instrumented Android UI coverage. Use the original signing key for an in-place update; do not uninstall or clear local data. See `AVATAR_PERMISSION_FIX.md` and `UPDATE_INSTALLATION.md`.

The package is source only: generated APKs, caches, machine-specific SDK paths, logs, dependency installations and signing keys are excluded.
