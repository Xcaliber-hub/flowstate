# Flowstate 1.1.2: avatar saving and permissions

## What this update fixes

Profile saving now waits for Firestore to accept the selected avatar. The editor stays open on an error instead of closing as if the save worked. It shows saving progress, a useful error, and confirmation only after the profile and group snapshots finish saving. If the profile saves but group synchronization fails, it says so explicitly and offers another Save attempt.

Profile updates and background member publishing share a lock, so a heartbeat cannot finish writing an old avatar after a successful profile change. Group snapshots replace the signed-in user's own member document instead of merging it. This removes obsolete fields from older app versions that would otherwise survive a merge and fail the rules' allowed-field validation. Local timer history, tasks, preferences, accounts, and group membership are not cleared.

All twelve existing artwork files and avatar IDs are unchanged. Initials reset remains available. This update does not use alternate IDs to bypass Firestore security.

## Why the old six can work while the new six fail

The source and rules you supplied already contain all twelve IDs. The emulator regression test reproduces the exact six-work/six-fail pattern when the active rules still contain only the original six. That makes the rules actually used by the installed app an important check; it is not proof of what is currently deployed in your live project. A source ZIP cannot change a published ruleset or an already-installed APK.

This project's unchanged Firebase resources point to **`flowstate-241f0`**, Cloud Firestore database **`(default)`**. A `google-services.json` file is not used: this app initializes its named Firebase app from `app/src/main/res/values/flowstate_firebase.xml`.

Permission messages now identify that configured project/database. Profile and group-write errors also identify the rejected document path, rather than showing only a generic permission error.

## Apply the update

1. In the Firebase console, select project **`flowstate-241f0`**. Open **Cloud Firestore → `(default)` → Rules**, replace the entire rules editor with the supplied `Flowstate-1.1.2-avatar-permissions-firestore.rules` (identical to `firebase/firestore.rules`), and click **Publish**. This is Firestore, not Storage or Realtime Database. The new rules' second line identifies Flowstate 1.1.2.
2. Alternatively, from this source's `firebase` directory, run:

   ```sh
   firebase deploy --only firestore:rules --project flowstate-241f0
   ```

   The included `.firebaserc` also pins that project. Nothing in this source update automatically deploys rules.
3. Build and install version **1.1.2**, Android version code **4**, over the existing app using its original signing key. Do not uninstall or clear app data. See `UPDATE_INSTALLATION.md`.
4. Save Cloud, Ember, Turtle, Jelly, Comet, and Bloom in turn. Check the profile and leaderboard, wait for a background sync, then restart/sign in again to check persistence. Also check an original avatar and initials reset.

If a denial remains, use the new message to distinguish `users/{uid}` from `groups/{groupId}/members/{uid}`. Confirm the running build uses the project shown, the rules were published to that exact project's `(default)` database, and the account is a member of that group. Do not switch to open/read-write-for-everyone rules.

## What was verified

See `BUILD_VERIFICATION.md` for the final build and emulator results. No live Firebase account was accessed, no production rules were deployed, and no physical-device synchronization or in-place upgrade was performed here. The legacy-field migration and old-six-rule behavior are reproduced in emulator tests, not assertions about the contents of your live documents.
