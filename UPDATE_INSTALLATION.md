# Updating Flowstate without uninstalling

## What changed

This source is version **1.1.2**, with Android `versionCode = 4` (previously 3). The package remains `com.flowstate.focus`. Release builds can now use a persistent signing key supplied through environment variables or private Gradle properties, rather than changing source files or embedding credentials in the ZIP.

**A version bump alone cannot fix a signing-key mismatch.** Android must recognize the new APK as an update to the installed app. Ordinary sideload updates need the same application ID and signing certificate, and each distributed release should increase `versionCode` beyond the installed version. Changing `versionName` alone is not sufficient.

No installed APK or its signing certificate was supplied, so the exact cause of the reported installation failure has not been confirmed. Other package-installation errors remain possible. This source update is not a claim of a successful on-device upgrade.

## Reuse the key that signed the installed app

If you have the original signing keystore, use that exact key and alias for every future release. If the installed app is a debug build, it was normally signed by the build machine's `~/.android/debug.keystore`; regenerating that file or building on a different machine commonly produces a different key. Do not alternate unrelated debug and release certificates for the same installed package.

If the original private key is unavailable, a replacement key cannot simply sign an in-place sideload update to that installation. Recover the original key first. Do not uninstall as a first troubleshooting step: uninstalling removes local timer history, tasks and preferences, and this app does not restore that history from Firestore. CSV export is not a full restorable app backup.

## Configure persistent release signing

Keep the keystore outside the project archive and back it up securely. Supply all four values using environment variables, or put them in your private user-level `~/.gradle/gradle.properties` (not the checked-in project file):

```properties
FLOWSTATE_KEYSTORE_FILE=/absolute/path/to/existing-flowstate-key.jks
FLOWSTATE_KEYSTORE_PASSWORD=YOUR_EXISTING_STORE_PASSWORD
FLOWSTATE_KEY_ALIAS=YOUR_EXISTING_KEY_ALIAS
FLOWSTATE_KEY_PASSWORD=YOUR_EXISTING_KEY_PASSWORD
```

Then build:

```sh
./gradlew :app:assembleRelease
```

Use `app/build/outputs/apk/release/app-release.apk`. Partial signing settings fail configuration instead of silently selecting another key. With no signing settings, debug builds use the normal local Android debug key, and release builds remain unsigned; an unsigned release cannot be installed. This source does not generate a new release key, include a keystore, or automatically recover an old key.

For the next release after this one, increase the version code above 4 while keeping the same key. If the installed app already has a version code greater than or equal to 4, use a higher code before distributing the next APK.

## Verify before installing

Compare the signer certificate of a previously installed/distributed APK and the new APK with Android SDK Build Tools:

```sh
apksigner verify --print-certs previous.apk
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The signing certificate SHA-256 digest should match for the normal same-key update path. Check both APKs' application IDs and version codes as well. Then install normally over the existing app, or use `adb install -r path/to/new.apk` on a connected development device. If it still fails, retain the exact installer/ADB error instead of deleting the old app; `INSTALL_FAILED_UPDATE_INCOMPATIBLE` indicates a signing mismatch, while `INSTALL_FAILED_VERSION_DOWNGRADE` indicates a lower version code.

A matching key and a higher code prepare normal updates; no physical-device update or preservation of local data has been tested here. See [Android's app-signing documentation](https://developer.android.com/studio/publish/app-signing) for signing and key-management details.
