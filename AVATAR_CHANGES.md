# Preset group avatars

## Included in the app

The six approved designs—Sprout, Sunbeam, Moon, Fox, Orbit and Summit—are bundled as local drawable resources, cropped from the approved artwork. In **Groups → Edit**, choose a preset and save, or choose **Use initial instead**. The picker has a live preview, selected-state text, accessibility selection semantics and a scrollable two-column layout.

The same choice appears on the profile card, weekly leaderboard and active/inactive Live-room members. The profile applies across the account's groups, not separately to each room. Existing accounts with no preset continue to display initials; unknown IDs also fall back safely to initials. The Live-room first row now reserves a slot for inviting a friend instead of crowding three members plus the invitation into one row.

## Saved without Storage

Only the preset ID is stored in Firestore: the private `users/{uid}` profile preserves the selection across sign-ins and group member documents share it with other group members. Images never leave the app. No upload flow, photo permissions, Firebase Storage, remote-image library or image-hosting dependency has been added. Configured Firebase client values are unchanged.

Deploy the supplied `firebase/firestore.rules` to the intended Firebase project before using this version; old rules do not allow the new `avatarId` member field. Rules deployment has not been performed as part of this task. Optional avatar fields support older documents while validating the approved ID allowlist and maintaining owner-only writes.

## Not included

The new preview contains Cloud, Ember, Turtle, Jelly, Comet and Bloom. These are review-only concepts and are deliberately absent from the APK resources, picker and approved ID allowlist. They require approval before implementation.

## Device checks

Check choosing each preset, cancelling an edit, changing name and avatar together, resetting to initials, sign-out/sign-in, switching accounts and viewing from a second group member's device. Test the picker and Live grid at larger font scales and on a narrow phone. Build, unit and authorization checks are summarized in `BUILD_VERIFICATION.md`; they do not replace an on-device visual test.
