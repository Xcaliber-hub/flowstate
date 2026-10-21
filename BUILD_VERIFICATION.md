# Build verification

The no-profile-photo source was checked using Gradle 8.9, Android SDK 35 and JDK 21:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- Debug app compilation and packaging: passed.
- Unit tests: 6 passed (live timer timestamp behavior and UTC weekly boundaries).
- Android lint: no errors; 61 warnings and 1 informational finding remain. This is not a zero-warning claim.
- Firestore emulator authorization tests: 8 passed. Reproduce with `cd firebase/tests && npm install && npm test`.
- Profile editing is name-only. Profile cards, the leaderboard and the Live tab use circular initial badges, with no photo picker, preview or remote photo loading.
- Firebase Storage, Coil and ExifInterface dependencies, image processing/upload code, bucket configuration and Storage deployment configuration have been removed. Groups configuration requires only Firebase application ID, API key and project ID.
- Existing Firestore rules are unchanged. Member writes retain an empty legacy `photoUrl` field for compatibility; the app neither reads nor displays photo URLs.

The ZIP is source only; generated APKs, build caches, machine-specific SDK paths and installed Node dependencies are excluded. No real Firebase project was configured or deployed, and no production accounts were created. On-device sign-in, profile-name editing, multi-user realtime behavior and visual rendering still require testing against your configured staging backend and a physical device/emulator. Existing backend photo data, if any, has not been deleted. See `firebase/GROUPS_SETUP.md` for setup and current single-device-per-account accounting limitations.
