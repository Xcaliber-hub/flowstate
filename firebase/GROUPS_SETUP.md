# Flowstate Groups Firebase setup

Use a **dedicated Firebase project for Flowstate**. Do not reuse Stride or another app's Firebase project. The repository explicitly initializes a named Firebase app (`flowstate-groups`) and does not require the Google Services Gradle plugin or `google-services.json`.

## Configure app values

Set the three string resources in `app/src/main/res/values/flowstate_firebase.xml` (or provide same-named build variant resources):

- `flowstate_firebase_application_id`
- `flowstate_firebase_api_key`
- `flowstate_firebase_project_id`

All three must be nonblank. Blank defaults leave Firebase unconfigured while the offline timer and local app continue to work. Keep project credentials out of source control; values here are public client configuration, not server secrets.

## Console and deployment

1. Create a Firebase project and register the Android package `com.flowstate.focus`.
2. In **Authentication → Sign-in method**, enable **Email/Password**.
3. Create the Cloud Firestore database. Firebase Storage is not used or required.
4. From the repository root, run `cd firebase && firebase deploy --only firestore:rules --project flowstate-241f0`. The checked-in `firebase/firebase.json` selects `firestore.rules`; select the dedicated project first (`firebase use <project-id>` or pass `--project <project-id>`). The included `.firebaserc` selects the current client project `flowstate-241f0`; the app uses its `(default)` database. When testing another project, change both the app client resources and the CLI target deliberately.

Groups use 12-character uppercase cryptographically random invite document IDs, max 20 members, direct group get by authenticated invite-code holder, and member-scoped realtime listing. The rules intentionally prohibit arbitrary member changes and group deletion; users can only add their own UID or remove their own UID and may only write their own member profile. Profiles support a name and optional preset `avatarId`: `sprout`, `sunbeam`, `moon`, `fox`, `orbit`, `summit`, `cloud`, `ember`, `turtle`, `jelly`, `comet` or `bloom`. Empty or missing IDs display initials. The private `users/{uid}` document stores the choice across sign-ins, and the app republishes it to the user's group member documents for the leaderboard and Live tab. Artwork is bundled locally; there are no remote images, photo uploads or Firebase Storage. The member `photoUrl` field remains empty for legacy shape compatibility and is never used as an image source. Publish the rules to the exact project/database used by the installed app: six-avatar rules reject the six new IDs, but the active live rules were not inspected here. See `../AVATAR_PERMISSION_FIX.md` for diagnosis and deployment steps. Legacy documents without an avatar ID remain supported.

Weekly totals are calculated from this installation's local Room history and clipped to the Monday 00:00 IST (Asia/Kolkata)-to-Monday 00:00 IST week. Existing rows are marked unassigned on first observation so a newly signed-in account does not inherit earlier local history. Newly observed rows are mapped to the signed-in UID in installation-local preferences. This cannot reconcile sessions across devices. The member document stores the latest value written by a device for that UID, **not a multi-device aggregate**; concurrent devices may overwrite leaderboard totals and live presence.

## Dependency requirements for build integration

Required app dependencies: Firebase BoM `com.google.firebase:firebase-bom:33.7.0`; BoM-managed `com.google.firebase:firebase-auth-ktx` and `com.google.firebase:firebase-firestore`; and `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0` for Task `await()`. The app build file already includes these dependencies. No Firebase Storage dependency or Google Services plugin is required.

## Local checks

The source includes a Firestore authorization suite. It uses only the `demo-flowstate` emulator project and never deploys rules or reads a real database:

```sh
cd firebase/tests
npm install
npm test
```

Node.js 20+ and Java 21+ are recommended for the included Firebase CLI version. The tests cover anonymous access, group creation, code-based self-join, membership-scoped queries, duplicate/member-tampering prevention, own-member writes, leaving, private account profiles, allowed preset IDs, unknown avatar IDs, URL-like values and non-string types, and legacy documents.

Weekly totals include recorded focus intervals; an in-progress focus block is shown live and is credited to the leaderboard after it is recorded. Breaks do not add focus time. Resetting app storage removes installation-local session ownership and history; this version does not restore that history from the cloud.

## Profile-save compatibility

Version 1.1.2 serializes profile and member writes, reports rejected operations, and keeps the editor open until synchronization succeeds. Member writes replace only the authenticated user's app-owned snapshot rather than merging obsolete fields. This does not delete local focus history or group membership. The rules retain owner-only writes and validate all twelve preset IDs; unknown IDs and image URLs remain rejected. The emulator suite now also reproduces stale-field merge rejection, repairs it by replacement, verifies multi-group readback, and reproduces old-six-rule rejection.
