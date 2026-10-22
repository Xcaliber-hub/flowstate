# Overall visibility changes

## Changes made

- Applied stronger theme-derived dark roles across default and dynamic dark schemes: lifted surfaces, brighter `onSurfaceVariant` copy, and more visible outline roles, while retaining the black app canvas and leaving light color roles unchanged.
- Increased dark glass-card opacity and made card borders clearly visible; preserved existing light-mode translucency. Floating navigation similarly uses a more opaque dark surface and visible dark outline, while retaining its light appearance. Unselected tab icons use the now-brighter theme secondary text color and selected tabs retain their filled accent state.
- Increased dark focus-ring and chart empty/track contrast so Liquid UI and chart styling do not erase the visual boundary against dark backgrounds. The dark search field inherits the stronger surface, text, placeholder, icon and outline theme roles.
- Increased floating navigation-bar opacity and made its outline fully theme-derived/opaque so the control remains distinct over scrolling content in light and dark themes. Each tab now has a minimum 48 dp touch target; selected state and tab semantics are preserved.
- Removed the 50% opacity treatment from completed task rows, which dimmed the title and priority/category/due-date information together. Completed titles retain a line-through and use the theme's `onSurfaceVariant` color; unfinished titles use `onSurface`.
- Made accent-color choices at least 48 dp in each dimension, exposed selection through radio-button semantics and added a spoken action label.
- Raised settings link rows to a minimum 48 dp touch height.

These are additive fixes; prior root-surface, GlassCard foreground, blocked-app, and group-action visibility changes were left intact. This visibility pass did not change Firebase values, features, dependencies or backend behavior; separate approved-avatar changes are documented in `AVATAR_CHANGES.md`. Existing theme-derived colors and Liquid UI behavior remain in place. Light theme color roles and glass opacity remain as before.

## Limitations and device checks

Source review is not a substitute for visual/accessibility testing. Build, unit and authorization verification passed; see `BUILD_VERIFICATION.md` for results. On device, test light/dark/system-following themes, dynamic color on/off, gesture and three-button navigation, display cutouts, large font/display scaling, narrow screens, talkback labels and selected-state announcements, and minimum tap targets. Check that the more opaque floating navigation control does not obscure content and that completed task details remain legible.
