# Build verification

The updated source was verified with Gradle 8.9, Android SDK 35 and JDK 21:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- Debug app compilation and packaging: passed.
- Unit tests: 9 passed, including the approved avatar catalog, allowed/invalid IDs, initials fallback, timer timestamps and UTC weekly boundaries.
- Android lint: no errors; 61 warnings and 1 informational finding remain. This is not a zero-warning claim.
- Firestore emulator authorization suite: 11 tests passed. Coverage includes the six approved IDs and empty reset, legacy missing-avatar documents, invalid URLs/types and review-only concepts, readback/update/reset, owner-only writes and existing group membership restrictions.

Reproduce authorization checks with `cd firebase/tests && npm install && npm test`. The emulator suite used only `demo-flowstate`, not the configured live project. No Firebase rules were deployed and no production accounts were created. **Deploy the updated Firestore rules to the intended project before using avatar saving**; old member rules reject `avatarId`.

The six approved avatar images are local resources. Chosen IDs are stored on the private account profile and shared member documents. No Firebase Storage, image upload flow, remote-image dependency or photo permissions were added. The configured Firebase resource file was compared byte-for-byte against the latest supplied source and is unchanged. The six additional concepts are excluded from app resources and allowed IDs pending approval.

Earlier visibility fixes are retained. Broader dark-mode improvements cover theme surfaces and secondary text, borders, glass cards, navigation, focus/chart tracks, completed tasks and settings controls. See `OVERALL_VISIBILITY_CHANGES.md`, `VISIBILITY_CHANGES.md` and `AVATAR_CHANGES.md`.

No Android emulator or physical-device visual test was performed. Compilation, unit tests and authorization tests do not establish final on-device contrast, text layout, accessibility behavior or real multi-user synchronization. Check light/dark/system modes, dynamic color, font scaling, narrow phones, avatar save/reset and re-sign-in against staging before release. Existing single-device focus-accounting limitations remain documented in `firebase/GROUPS_SETUP.md`.

This ZIP is source only. Generated APKs, build caches, emulator logs, installed Node dependencies and machine-specific SDK paths are excluded.
