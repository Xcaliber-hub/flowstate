# Build verification

## Checks passed

The final source was checked with Gradle 8.9, Android SDK 35 and JDK 21:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- Debug compilation and APK packaging passed after the final accent-layout fix.
- All 10 JVM unit tests passed: the complete twelve-preset catalog and labels, original-ID preservation, new-ID acceptance, invalid-ID rejection, initials fallback, timer timestamps and UTC weekly boundaries.
- Android lint reported no errors, 61 warnings and 1 informational finding. This is not a zero-warning claim.
- All 12 Firestore emulator authorization tests passed. Coverage includes all twelve IDs and empty reset in private and member documents, migration from an original ID to each new ID, readback/reset, missing-avatar legacy documents, unknown IDs/URLs/non-string rejection, owner-only writes and existing group membership restrictions.
- Persistent release signing was validated with `:app:validateSigningRelease :app:signingReport` using an isolated disposable test keystore. The report selected the configured `persistentRelease` key. The test key was deleted and is not in this source ZIP. This does not verify compatibility with the user's installed APK.

Run authorization checks with `cd firebase/tests && npm install && npm test`. Tests used only the `demo-flowstate` emulator project, not the configured live project. No Firebase rules were deployed and no production accounts were created.

## Preserved and changed

All original PNG resources, configured Firebase client values and group repository code were compared byte-for-byte against the supplied source and are unchanged. Earlier app-wide dark-mode UI/theme code is unchanged, except for the requested avatar-picker additions and the accent-swatch layout fix in Settings.

The app now bundles all twelve avatar presets. No Firebase Storage, photo upload flow, remote-image dependency or photo permissions were added. **Publish the supplied twelve-avatar Firestore rules again before the six new choices will save.** The previously published six-avatar rules do not allow Cloud, Ember, Turtle, Jelly, Comet or Bloom.

The six original accent colors and saved selection/theme logic remain unchanged. Settings uses explicitly sized 48dp swatches and wraps them across rows on narrow screens. Dynamic color still takes precedence on supported devices; turn it off to pick a preset.

The app version is now 1.1.1, code 3, with the same `com.flowstate.focus` application ID. Persistent release signing can be supplied through private Gradle properties or environment variables. See `UPDATE_INSTALLATION.md`: updating without uninstalling still requires a signing key compatible with the installed app, and a suitable version code. No production signing key was supplied or recovered.

## Still needs a device check

No Android-emulator or physical-device visual test, installation upgrade, local-data preservation test, or live Firebase synchronization test was performed. Compilation and unit tests do not prove on-device contrast, swatch layout or accessibility behavior. Check all twelve avatars, initials reset, sign-in persistence, preset colors, Dynamic color, light/dark/system modes, font scaling and narrow phones against staging. Existing single-device focus-accounting limitations remain documented in `firebase/GROUPS_SETUP.md`.

This ZIP contains source only. Generated APKs, build caches, emulator logs, installed Node dependencies, signing keys and machine-specific SDK paths are excluded.
