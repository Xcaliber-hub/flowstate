> Historical notes for the prior update. For the current 1.1.2 fix, see [Avatar and permission fix](AVATAR_PERMISSION_FIX.md).

# Twelve preset group avatars

## Included in the app

The original Sprout, Sunbeam, Moon, Fox, Orbit and Summit presets are unchanged. Cloud, Ember, Turtle, Jelly, Comet and Bloom have now been added from the approved preview artwork. All twelve designs are bundled local drawables. No photo uploads, photo permissions, Firebase Storage or remote-image dependency has been introduced.

In **Groups → Edit**, choose a preset and save, or choose **Use initial instead**. The existing scrollable two-column picker, live preview, selected-state text and accessible selection semantics remain. The six new choices fit in the existing scrollable profile dialog without moving Save outside the dialog.

The same choice appears on the profile card, weekly leaderboard and active/inactive Live-room members. Choices apply across the account's groups. Existing accounts with no preset or unknown IDs still display initials. The original six resource files and stored IDs are unchanged.

## Publish the rules again

**The previously published six-avatar rules are not enough for these six new choices.** Publish this ZIP's `firebase/firestore.rules` (also supplied separately) to the existing Flowstate Firebase project before saving Cloud, Ember, Turtle, Jelly, Comet or Bloom. Until then, Firestore will reject those new IDs. There is no need to recreate the database or change the Firebase client configuration.

The allowed IDs are `sprout`, `sunbeam`, `moon`, `fox`, `orbit`, `summit`, `cloud`, `ember`, `turtle`, `jelly`, `comet`, and `bloom`. Empty IDs reset to initials; missing fields remain supported for older documents. Existing owner-only writes and group authorization restrictions are preserved. No rules were deployed by this source-update task.

Only the preset ID is stored on the private account profile and shared member documents. Artwork never needs to be uploaded. Configured Firebase client values and app-wide dark-mode visibility changes are preserved.

## Device checks

Check each preset, cancelling an edit, changing name and avatar together, resetting to initials, sign-out/sign-in and viewing from a second group member's device. Check the bottom picker rows with larger fonts and a narrow phone. Build, unit and authorization tests are summarized in `BUILD_VERIFICATION.md`; they do not replace on-device visual and synchronization tests.
