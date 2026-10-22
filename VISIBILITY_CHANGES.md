# Visibility fixes and avatar review

## Visibility changes

- Added a full-screen Material `Surface` at the app root, pairing the active theme's `background` with `onBackground`. `MaterialTheme` alone neither paints a background nor sets the default foreground. This prevents plain labels and headings from using a default black foreground over the activity window's dark background, and keeps light mode genuinely light.
- Explicitly paired translucent glass-card surfaces with `onSurface` text instead of relying on an automatic color lookup for an alpha-modified color.
- Gave the blocked-app category chips filled theme surfaces, readable labels and stronger outlines. The search field now explicitly pairs its background, entered text, placeholder and search icon. Checkbox outlines use `onSurfaceVariant`, with checked and checkmark colors paired to the accent.
- Gave the bottom group actions (`Share invite` and `Leave group`) theme-aware filled surfaces, readable labels and visible outlines. The existing busy/disabled state and leave confirmation remain intact.

All colors derive from the active Material scheme; no permanent white-on-light or black-on-dark overrides were introduced. App blocking, search, sharing, group membership and backend behavior are unchanged. Existing Firebase configuration is preserved.

## Approved avatar update

The user approved Sprout, Sunbeam, Moon, Fox, Orbit and Summit. Those original six designs remain bundled, selectable and persisted by preset ID, with an initials fallback. See `AVATAR_CHANGES.md`. Cloud, Ember, Turtle, Jelly, Comet and Bloom are now also approved and included, bringing the picker to twelve presets. No photo uploads, photo permissions or Firebase Storage were added. The earlier visibility fixes listed above remain in place; broader visibility improvements are documented in `OVERALL_VISIBILITY_CHANGES.md`.

## Device review

Before release, check the blocked-app screen and the group actions in light mode, dark mode and system-following mode, with dynamic color both enabled and disabled. Verify the keyboard/search state, selected and unselected checkboxes, category chips, busy/disabled actions, large text, and a narrow phone screen. The source changes do not substitute for on-device visual verification.
