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
4. From the repository root, run `cd firebase && firebase deploy --only firestore:rules`. The checked-in `firebase/firebase.json` selects `firestore.rules`; select the dedicated project first (`firebase use <project-id>` or pass `--project <project-id>`). Review the initial deployment in a non-production project first.

Groups use 12-character uppercase cryptographically random invite document IDs, max 20 members, direct group get by authenticated invite-code holder, and member-scoped realtime listing. The rules intentionally prohibit arbitrary member changes and group deletion; users can only add their own UID or remove their own UID and may only write their own member profile. Profiles are name-only and displayed with circular initials avatars; the app does not upload, read, or display remote profile photos. Member writes may retain an empty `photoUrl` field for compatibility with existing Firestore rules; it is not used as a photo source.

Weekly totals are calculated from this installation's local Room history and clipped to the UTC Monday-to-Monday week. Existing rows are marked unassigned on first observation so a newly signed-in account does not inherit earlier local history. Newly observed rows are mapped to the signed-in UID in installation-local preferences. This cannot reconcile sessions across devices. The member document stores the latest value written by a device for that UID, **not a multi-device aggregate**; concurrent devices may overwrite leaderboard totals and live presence.

## Dependency requirements for build integration

Required app dependencies: Firebase BoM `com.google.firebase:firebase-bom:33.7.0`; BoM-managed `com.google.firebase:firebase-auth-ktx` and `com.google.firebase:firebase-firestore`; and `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0` for Task `await()`. The app build file already includes these dependencies. No Firebase Storage dependency or Google Services plugin is required.

## Local checks

The source includes a Firestore authorization suite. It uses only the `demo-flowstate` emulator project and never deploys rules or reads a real database:

```sh
cd firebase/tests
npm install
npm test
```

Node.js 20+ and Java 21+ are recommended for the included Firebase CLI version. The tests cover anonymous access, group creation, code-based self-join, membership-scoped queries, duplicate/member-tampering prevention, own-member writes, leaving, and private account profiles.

Weekly totals include recorded focus intervals; an in-progress focus block is shown live and is credited to the leaderboard after it is recorded. Breaks do not add focus time. Resetting app storage removes installation-local session ownership and history; this version does not restore that history from the cloud.
