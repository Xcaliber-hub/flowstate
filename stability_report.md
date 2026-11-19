# Flowstate Android 1.8.6 stability review, with fixes in 1.8.7

**Scope:** static review of `/workspace/work/fs/src` (Android `app/`, read-only) and the release APK `Flowstate-1.8.6-7e47b5128675.apk` (versionCode 40, target/compile SDK 35, min 26). I inspected the APK's manifest and dex with androguard.
**Fixes:** `/workspace/work/fs/fixed-src` (versionCode 41, versionName 1.8.7, README changelog section "1.8.7").
**Build:** not built. No Android SDK, JDK or Gradle exists in this sandbox. Edits were checked for brace and parenthesis balance only, so a compile pass is needed before release.
**Evidence:** there are no device logs or crash reports. Confidence labels:
- **confirmed**: the code path demonstrably does the wrong thing. For the widget injection bug, the release dex confirms it.
- **likely**: the defect is real, but how often it fires in the field is unknown.
- **possible**: the defect needs a specific device, configuration or timing.

Line numbers below refer to the **original** 1.8.6 source (`src/app/src/main/java/com/flowstate/focus/...`).

---

## Summary

| Severity | Count |
|---|---|
| Critical (chat draft loss, the user-reported bug) | 2 |
| High | 4 |
| Medium | 9 |
| Low | 4 |
| Checked, no change needed / informational | 7 |

**Disappearing chat draft, root causes (both confirmed):**
1. The chat screen **pops itself whenever `selectedGroup` or `user` is momentarily null.** The most common trigger is process death: the user switches apps mid-message, Android kills Flowstate, and on return Navigation restores the chat before Firebase auth and groups have reloaded. The draft lived only in that destination's `rememberSaveable`, so popping it destroyed the text.
2. **Re-navigation from a chat notification** replaces the chat destination: it pops to the start destination, then pushes a new `GROUP_CHAT` entry without `restoreState`, which gives an empty draft. The Group focus widget's tap also pops the chat.

Two further confirmed contributors:
- **Edit overwrote the draft**, and Cancel cleared it.
- **The 1.8.5 "clear only after Firestore accepts" logic** is correct for a hard failure. But it awaits the *server* acknowledgement, which never arrives offline. Send stays disabled with the text in the box, and leaving the chat then loses the text. The coroutine is also cancelled on leaving, which skipped the repository's failure handling.

**Crash root causes:** none can be pinned to one stack trace without logs. The structural causes, in order of likely impact:
1. **Every app-wide singleton or service coroutine scope lacked an exception handler.** Any exception thrown inside `scope.launch` there (Room errors while applying cloud pulls, session inserts, FCM topic sync, widget refresh) goes to the thread's default handler and kills the process.
2. **`GroupsRepository` mutated its listener collections from the main thread and from IO threads at once.** Sign-up runs `acceptAuth` twice concurrently, which can throw `ConcurrentModificationException`.
3. Several configuration-specific `IllegalArgumentException`/`IllegalStateException` paths: the narrow-window `coerceIn` calls, foreground-service start/stop races, and in-app update failures.

---

## Critical

### C1. The chat closes itself and drops the draft when the group or account is briefly null (process death)
- **Where:** `ui/groups/GroupChat.kt:215` (`if (group == null || state.user == null) { LaunchedEffect(Unit) { onBack() }; return }`) and `:241` (draft only in `rememberSaveable`). `GroupsRepository` is rebuilt empty after process death: `selectedGroup = null` until `acceptAuth` → `refreshGroups` → the groups snapshot. `selectedGroup` was also not persisted, so a restart picked `groupList.firstOrNull()`.
- **Who:** anyone who leaves the app mid-message and returns after Android killed it. This is common on 3–4 GB phones, the core JEE/NEET audience. Also any transient sign-out from `acceptAuth(uid=null)`.
- **Confidence:** confirmed (the code path is deterministic once the repository state is null on first composition).
- **Fix:**
  - New `data/groups/ChatDraftStore.kt` keeps the draft per group in SharedPreferences, written as the user types and read back when the chat opens. `GroupChatScreen` uses `rememberSaveable(group.id) { vm.draftFor(group.id) }`.
  - The null branch now shows a spinner and waits up to 10 s (`CHAT_RESTORE_WAIT_MS`) before `onBack()`.
  - `GroupsRepository` remembers the selected group (`groups_selection` prefs) and prefers it when groups reload.
  - Drafts and the remembered group are cleared on sign-out.

### C2. A chat notification or widget tap replaces the open chat
- **Where:** `ui/FlowstateAppUi.kt:117-119`: `selectGroup`, then `navigate(GROUPS){ popUpTo(start){saveState=true} }`, then `navigate(GROUP_CHAT)` with no `restoreState`, which creates a new entry with an empty draft. The widget path is at `:131-135`.
- **Who:** a user typing in one group who taps a chat push (pushes are suppressed only while the chat is visible, so a backgrounded chat gets them). Also anyone who taps the Group focus widget.
- **Confidence:** confirmed.
- **Fix:** if the current destination is already `GROUP_CHAT`, only `selectGroup()` is called; the screen switches groups with a per-group draft. In every other path the draft now comes back from `ChatDraftStore`.

---

## High

### H1. App-wide coroutine scopes have no exception handler, so any stray exception crashes the process
- **Where:**
  - `data/groups/GroupsRepository.kt:91`
  - `data/sync/CloudSync.kt:73`, with unguarded launches at `:105` (`onSignedIn`), `:150` (`handleLock`) and `:160` (`applyPulled`: Room inserts and deletes, `getLong`/`getTimestamp` decoding)
  - `service/TimerEngine.kt:48`, with `:358` `scope.launch { sessions.insert(entity) }` (Room plus a widget refresh broadcast)
  - `push/ChatPushSync.kt:48/59` (`FirebaseMessaging.getInstance()` outside `runCatching`)
  - `FlowstateApp.kt:52-56` (widget refresh collector)
  - `TimerService`, `AmbientController`, `FocusBlockerService` and both widget providers
- **What goes wrong:** `CoroutineScope(SupervisorJob() + Dispatchers.X)` without a `CoroutineExceptionHandler` delivers uncaught exceptions to `Thread.defaultUncaughtExceptionHandler`, which on Android is a crash. Reachable triggers include a full or locked disk, an SQLite error, a DataStore `IOException`, a Firestore client already terminated, and a missing default FirebaseApp.
- **Who:** all signed-in users (sync) and everyone recording sessions. It shows up intermittently, which matches "many users see crashes" with no clear pattern.
- **Confidence:** likely. The structure is confirmed; the field frequency of each trigger is unknown.
- **Fix:**
  - New `util/AppScopes.kt` builds scopes with `SupervisorJob + CoroutineExceptionHandler` that log; all of the scopes above use it.
  - Explicit `runCatching` around `onSignedIn`, `handleLock`, `applyPulled`, the session insert, the topic `apply()` and the widget request.

### H2. Data race on `GroupsRepository` listener bookkeeping (CME / HashMap corruption)
- **Where:**
  - `GroupsRepository.kt:161`: the AuthStateListener does `scope.launch { acceptAuth() }` on IO.
  - `:170`: `signUp` also calls `acceptAuth` directly, so it runs twice concurrently on sign-up.
  - `:573/721`: `cleanupListeners()` runs on IO.
  - `:604-675`: `attachGroups`, `attachPings` and `attachMembers` are mutated from main-thread snapshot callbacks and from IO (`refreshGroups`, `createGroup`, `joinGroup`).
  - `selectGroup` (`:229`) runs from the UI thread.
  - The state is `memberListeners` (ArrayList), `pingListeners`/`pingsByGroup` (HashMap) and `chatListener`.
- **What goes wrong:** concurrent `clear()`/iterate/`put` throws `ConcurrentModificationException` or corrupts the map. That happens on an IO coroutine (crash, see H1) or on the main thread inside a Firestore callback (crash).
- **Who:** new sign-ups (most likely) and sign-out/sign-in or create/join while snapshots are arriving.
- **Confidence:** likely for sign-up; possible otherwise.
- **Fix:** a single `listenerLock`, used via `synchronized`, around all listener and map mutation (`openChat`/`closeChat`, `attachGroups`/`Pings`/`Members`, `clear*`, `cleanupListeners`, `refreshPings`, `dismissPing`, `publishPings`). `activeUser` and `groupList` are now `@Volatile`. Firestore `add`/`remove` are non-blocking, so holding the lock does not deadlock.

### H3. Edit mode destroys the user's draft, and rotation turns an edit into a new message
- **Where:**
  - `GroupChat.kt:459`: Edit sets `draft = m.text`, overwriting the text being typed.
  - `:409`: Cancel edit does `draft = ""`.
  - `:288`: Reply while editing does the same.
  - `:491`: Delete of the edited message does the same.
  - `:244`: `editing` is plain `remember`. After rotation, dark-mode or font-scale recreation, edit mode is gone but the saved draft still holds the edited text, and Send posts it as a duplicate new message.
- **Who:** anyone who edits a message mid-conversation.
- **Confidence:** confirmed.
- **Fix:**
  - Edit state is saved by id (`editingId` and `editingOriginal` in `rememberSaveable`).
  - The pre-edit draft is stashed (`stashedDraft`) and restored on cancel, save, reply or delete (`endEdit`).
  - Drafts are not persisted while editing.

### H4. The 1.8.5 send logic waits for the server, which hangs offline, and leaving the chat skips failure handling
- **Where:** `GroupChat.kt:258-286` and `GroupsRepository.kt:392-436` (`ref.set(data).await()`).
- **Is the 1.8.5 claim correct?** Partly. It does clear only after `set()` succeeds, so a rejected write keeps the text. But Firestore's `set()` Task completes only on **server** acknowledgement:
  - **Offline it never completes.** The message already appears in the list as pending (from the local cache), yet the box keeps the text with Send disabled (`submitting`), which looks broken.
  - **Leaving the chat cancels the awaiting coroutine** (`rememberCoroutineScope`). The repository's `catch`, which removes the pending bubble, shows the error and keeps the text, never runs if the server later rejects the write. The text in the popped destination is gone.
  - **Typed-ahead text is blocked** while a send is pending, because Send is disabled.
- **Confidence:** confirmed (documented Firestore Task semantics plus the code).
- **Fix:**
  - The box clears immediately. Firestore queues the write durably and shows it as pending.
  - Failure handling moved onto the Task's `addOnFailureListener` in the repository, so it runs even after the screen is gone. It removes the pending bubble, shows the error and calls `drafts.restoreIfEmpty(groupId, text)`. If the box is still empty, the UI puts the text and reply target back.
  - Edits work the same way: edit mode closes at once and reopens if the server rejects the edit.
  - Starter chips use a separate `sendStarter` that never touches the box.

---

## Medium

### M1. Widgets never refresh on demand: the Hilt field is not injected on the custom refresh action
- **Where:** `widgets/GroupFocusWidgetProvider.kt:27-35` and `widgets/DailyProgressWidgetProvider.kt:27-35`. For `ACTION_GROUP`/`ACTION_DAILY` they call `onUpdate` **without** `super.onReceive()`.
- **What goes wrong:** Hilt injects receivers inside the generated base class's `onReceive`. The release dex shows `A2/p.onReceive` doing the injection, and the subclass's refresh branch skips `invoke-super`. `dataSource` stays uninitialized, the coroutine throws `UninitializedPropertyAccessException`, and it is caught and logged. Every refresh (on each `MainActivity.onResume`, each session insert, each presence change) silently fails. Widgets update only on the 30-minute `updatePeriodMillis`. This is not a crash.
- **Confidence:** confirmed (source and APK bytecode).
- **Fix:** `super.onReceive(context, intent)` is always called first; `AppWidgetProvider` ignores unknown actions. `getInstance`/`getAppWidgetIds` are guarded.

### M2. `WidgetRefresh.request` is unguarded on hot paths
- **Where:** `widgets/WidgetRefresh.kt:26-29`, called from `MainActivity.kt:52` (every `onResume`), `data/repo/Repositories.kt:27/32` (every session insert/delete, inside TimerEngine's scope) and the `FlowstateApp` collector.
- **What goes wrong:** `AppWidgetManager.getInstance()` returns null on devices without `FEATURE_APP_WIDGETS` (some Go/TV/automotive builds), which causes an NPE. Binder calls can throw `DeadSystemException`/`RuntimeException`.
- **Confidence:** possible.
- **Fix:** wrapped in `runCatching` with a null check.

### M3. TimerService `stopSelf()` can discard a pending `startForegroundService()` ("did not then call startForeground")
- **Where:** `service/TimerService.kt:93`. The observer calls `stopSelf()` when the timer is idle and ambient is off.
- **What goes wrong:** if a new `startForegroundService()` is already queued, for example "Start" tapped as the last phase ends on a background thread, or ambient `ensureRunning`, the unconditional `stopSelf()` brings the service down before that start's `startForeground()`. On Android 9+ the system then crashes the app with `RemoteServiceException`/`ForegroundServiceDidNotStartInTimeException`.
- **Confidence:** possible (needs a timing window).
- **Fix:**
  - Track `lastStartId`; the observer uses `stopSelfResult(lastStartId)`, which is a no-op if a newer start exists.
  - `onStartCommand` stops itself with its own `startId` when it arrives with nothing to show.
  - The `ACTION_STOP` path no longer calls `startForeground()` on an already-stopping service.

### M4. `FocusViewModel.startService()` is unguarded
- **Where:** `ui/focus/FocusViewModel.kt:196`, called from `init` (`:105`), `start`, `startBreak` and `takeOver`.
- **What goes wrong:** `ForegroundServiceStartNotAllowedException` (an `IllegalStateException`) on Android 12+ if the view model is created or used while the app is not allowed to start a foreground service, for example an activity restored without being visible. Elsewhere, `TimerService.ensureRunning` already guards this.
- **Confidence:** possible.
- **Fix:** `runCatching` plus a log. The engine keeps running, and the notification returns via `ensureRunning` on the next change.

### M5. The collapsing nav bar throws when narrower than one nav height
- **Where:** `ui/components/FloatingNavBar.kt:147` and `:154`, `coerceIn(h, w)`.
- **What goes wrong:** `IllegalArgumentException: Cannot coerce value to an empty range` whenever `constraints.maxWidth < NavBarHeight` in px: a zero or narrow measurement pass, split screen or a free-form window, or the largest display size.
- **Confidence:** possible.
- **Fix:** the lower bound is `min(h, w)`.

### M6. Ambient sound sliders throw at zero width
- **Where:** `ui/components/Expressive.kt:249` (`SplitSlider`), `coerceIn(hw/2, size.width - hw/2)`.
- **What goes wrong:** each row in `AmbientBar.kt:293-325` has about 190 dp of fixed parts plus the slider weight. With a window narrower than that, the slider measures 0 px and drawing throws. Taps also divided by zero width, giving NaN volume (same in `WavySlider`).
- **Confidence:** possible.
- **Fix:** skip drawing below 2× the handle width; ignore input at width 0 in both sliders.

### M7. In-app update: download and install exceptions escape `viewModelScope`
- **Where:** `data/update/ApkInstaller.kt:46` (`dm.enqueue`: a disabled Download Manager gives `IllegalArgumentException`; a null `getExternalFilesDir` gives `SecurityException`/`IllegalStateException`), `:72`/`:81` (`startActivity`: `ActivityNotFoundException`; `FileProvider`: `IllegalArgumentException`), and `ui/update/UpdateViewModel.kt:94`.
- **Who:** users tapping Update. The app self-updates from GitHub, so every user who updates goes through this flow.
- **Confidence:** possible.
- **Fix:** `download` returns null on failure; `install` returns a Boolean; the view model shows `Failed`. The permission-settings launch is guarded.

### M8. The chat goes dead after an auth re-run
- **Where:** `GroupsRepository.kt:573/721`. `acceptAuth` → `cleanupListeners()` → `closeChat()` + `ChatState()`. The screen's `DisposableEffect(group.id)` (`GroupChat.kt:216`) never reopens it, so the user sees an endless spinner and an empty chat.
- **Confidence:** possible (needs the AuthStateListener to fire again while the chat is open).
- **Fix:** `LaunchedEffect(group.id, chat.groupId, user)` reopens the chat if `chat.groupId != group.id`. `openChat` is idempotent.

### M9. An empty cached groups snapshot clears the selection
- **Where:** `GroupsRepository.kt:609-616`. An empty `isFromCache` first snapshot (a fresh install or cleared cache, offline) sets `selectedGroup = null`, which closes the chat through C1.
- **Confidence:** possible.
- **Fix:** ignore an empty cache-only snapshot while a group list is already known.

---

## Low

### L1. The ambient audio thread can die with an uncaught RuntimeException
- **Where:** `service/ambient/AmbientPlayer.kt:107` catches only `InterruptedException`. `t.pause()`/`t.flush()` (`:141`) throw `IllegalStateException` on an uninitialized track after audio-server death. The raw `Thread` then crashes the process.
- **Confidence:** possible.
- **Fix:** catch and log `RuntimeException` (the thread ends; the next play starts a new one); `pause`/`flush` are wrapped.

### L2. DataStore corruption is unrecoverable
- **Where:** `data/prefs/SettingsRepository.kt:25`, `data/ambient/AmbientRepository.kt:24`, `data/sync/TimerLock.kt:24`.
- **What goes wrong:** reads map `IOException` to defaults, but every `edit()` keeps throwing `CorruptionException`. `deviceStore.data.first()` has no catch, so `deviceId()` throws inside CloudSync.
- **Confidence:** possible (rare).
- **Fix:** `ReplaceFileCorruptionHandler { emptyPreferences() }` on all three. The device id is regenerated if its file was corrupt.

### L3. Avatars decoded at full size per instance on the main thread
- **Where:** `ui/groups/PresetAvatarPicker.kt:77`, `painterResource()` of 440–464 px WebPs. That is about 0.85 MB each per composable instance, with no cross-instance cache, in every chat row and leaderboard entry.
- **What goes wrong:** jank, GC churn, and OOM risk on low-RAM phones.
- **Confidence:** likely as a performance issue; OOM possible.
- **Fix:** a process-wide `LruCache` of `inSampleSize=2` bitmaps (232 px, still at least the 58 dp xxhdpi size).

### L4. The selected group is not persisted
- **What goes wrong:** after a restart the app opens the first group, not the one in use. A restored chat could show a different group.
- **Confidence:** confirmed.
- **Fix:** remembered in prefs, cleared on sign-out (part of the C1 fix).

---

## Checked: no change needed, or informational

1. **R8/ProGuard** (`app/proguard-rules.pro`, minify and shrinkResources on):
   - No Firestore `toObject`/`@PropertyName` reflection; every document is mapped by hand (`getString`/`getLong`/`get`).
   - Room, Hilt, Firebase and Compose ship consumer rules; the dex contains the Hilt base classes and the injection code.
   - Enum persistence (`name`/`valueOf`) is R8-safe.
   - `org.json` comes from the platform.
   - The 24 avatar WebPs survive resource shrinking (the widget's `getIdentifier` also works, since `R.drawable.avatar_*` are referenced directly).
   - The two `-keep` lines for services are redundant but harmless.
   - **No R8-induced crash found.**
2. **Manifest (APK):**
   - `TimerService` `foregroundServiceType=specialUse|mediaPlayback` (`0x40000002`) with both FGS permissions and the special-use subtype property: correct for Android 14/15.
   - All PendingIntents use `FLAG_IMMUTABLE`.
   - Exact alarms are guarded by `canScheduleExactAlarms()`, with an inexact fallback and the `set*` calls in `runCatching`.
   - Chat notifications check `POST_NOTIFICATIONS`. `AlertNotifier` posts without checking; that drops silently on 13+ and does not crash.
   - **Play-policy risk (not a crash):** `USE_EXACT_ALARM` is restricted to alarm/calendar-category apps. Consider using only `SCHEDULE_EXACT_ALARM` if this ships on Play.
3. **`windowSoftInputMode=adjustResize` and IME insets:** not a cause of draft loss. There is no activity recreation and the composer stays in the same composition. `GroupChat.kt:252` reads `WindowInsets.ime` at the top of the screen, which recomposes the whole chat each IME animation frame. That is a performance cost only and was not changed.
4. **LazyColumn keys:** chat (`m.id`), tasks, exams, sessions and syllabus (chapter strings, verified unique per subject in `syllabus.json`) all have stable, unique, Bundle-saveable keys.
5. **Room migrations 1→2, 2→3 and 3→4:** `CREATE` SQL matches the exported `schemas/3.json` and `4.json`. No destructive fallback. (There is no `2.json` to diff 1→2 against; it was checked by reading.)
6. **Accessibility service (strict mode):** the launcher is not in `strictAllowed`, so `GLOBAL_ACTION_HOME` followed by the launcher's own window event triggers `returnToApp()` again. This is by design (it bounces back to Flowstate) and is not a crash.
7. **Widget cold start:** a widget update in a cold process builds `GroupsRepository`, which initializes Firebase and starts presence publishing in the background. Heavier than needed but not a crash; not changed.

---

## Files changed in `/workspace/work/fs/fixed-src`

**New:**
- `app/src/main/java/com/flowstate/focus/util/AppScopes.kt`
- `app/src/main/java/com/flowstate/focus/data/groups/ChatDraftStore.kt`

**Modified** (all under `app/src/main/java/com/flowstate/focus/`):
- `FlowstateApp.kt`
- `data/ambient/AmbientRepository.kt`
- `data/groups/GroupsRepository.kt`
- `data/prefs/SettingsRepository.kt`
- `data/sync/CloudSync.kt`
- `data/sync/TimerLock.kt`
- `data/update/ApkInstaller.kt`
- `push/ChatPushSync.kt`
- `service/FocusBlockerService.kt`
- `service/TimerEngine.kt`
- `service/TimerService.kt`
- `service/ambient/AmbientController.kt`
- `service/ambient/AmbientPlayer.kt`
- `ui/FlowstateAppUi.kt`
- `ui/components/Expressive.kt`
- `ui/components/FloatingNavBar.kt`
- `ui/focus/FocusViewModel.kt`
- `ui/groups/GroupChat.kt`
- `ui/groups/GroupsViewModel.kt`
- `ui/groups/PresetAvatarPicker.kt`
- `ui/update/UpdateViewModel.kt`
- `widgets/DailyProgressWidgetProvider.kt`
- `widgets/GroupFocusWidgetProvider.kt`
- `widgets/WidgetRefresh.kt`

**Also:** `app/build.gradle.kts` (versionCode 41, versionName 1.8.7) and `README.md` (1.8.7 section). No Firestore rules, cloud data, desktop module or push-worker changes.

## Recommended before release
1. Build `:app:assembleRelease` and run the existing unit tests (`MigrationSchemaTest`, `WidgetRemoteViewsLayoutTest`, …). None of the changes was compiled here.
2. Manual checks:
   - Type a chat message, then run `adb shell am kill com.flowstate.focus` with the app in the background, and reopen. The same chat should show with the draft.
   - Send in airplane mode: the message is pending and the box clears; the message is delivered when the phone reconnects.
   - Edit, then cancel: the original draft is back.
   - Rotate while editing.
   - Tap a chat push while in another group's chat.
   - Add both widgets and open the app: the widgets should refresh immediately.
   - Split-screen Flowstate to its narrowest and open the ambient sheet.
3. Add Firebase Crashlytics, or at least a persisted uncaught-exception log. The crash ranking above is inference; real stack traces would confirm or reorder it.
