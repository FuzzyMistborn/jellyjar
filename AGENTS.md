# JellyJar — Agent Notes

Project context and architecture live in `CLAUDE.md`. This file tracks on-device testing: what has been dogfooded on the emulator, what hasn't, and how to drive it.

## Building (read this first)
**This host can build and run the app.** The Android SDK is at `~/Android/Sdk` (installed via the Android Studio flatpak). Always pass `ANDROID_HOME=~/Android/Sdk` — without it Gradle stops with "SDK location not found", which is a missing env var, not a missing SDK. Never tell the user there's no Android SDK/tooling here; compile (`cd android && ANDROID_HOME=~/Android/Sdk ./gradlew :app:compileDebugKotlin -q`) and build-verify before reporting changes done.

## Dogfooding setup
- Emulator: AVD `Pixel_Tablet` (API 36 tablet), defined under Android Studio's flatpak config (`~/.var/app/com.google.AndroidStudio/config/.android/avd`). Usually started by the user.
- Build + install: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew :app:installDebug` (package `com.fuzzymistborn.jellyjar.debug`). A reinstall keeps app data.
- Android SDK is at `~/Android/Sdk` (installed via the Android Studio flatpak). Gradle doesn't find it on its own — without `ANDROID_HOME=~/Android/Sdk` it stops with "SDK location not found". For a quick compile check without installing: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew :app:compileDebugKotlin -q` (takes a few minutes cold). `adb` is in `~/Android/Sdk/platform-tools`. So "no Android tooling in this environment" is no longer true here: build-verify changes before reporting them done
- **Never** `pm clear` / uninstall the app — that wipes the sign-in, Press URL, download folder and download DB, and the user has to set it all up again. For a clean first-run state, create a throwaway Android user (`pm create-user`, `pm install-existing --user N`, `am switch-user N`), then switch back and `pm remove-user N`.
- Network: `adb shell cmd connectivity airplane-mode enable|disable` toggles the emulator's network without dropping adb.
- Driving the UI: `uiautomator dump` + tap by text works for Compose screens and sheets, but the player overlay is invisible to it (the video surface covers it) — use coordinates there. The emulator's floating keyboard means `BACK` after typing leaves the screen instead of closing a keyboard.
- Clean up afterwards: test downloads (Downloads → Delete All), Jellyfin watched/resume state changed by playback (toggle watched off on Detail), and Press jobs created by test downloads (`DELETE /jobs/{id}` — needs the user's OK).
- PR text: describe behaviour only; don't name titles from the user's library.

## Verified on the emulator (2026-11-10)
- Browse: home rows, library grid, server-side search/sort/genre, Detail and Season screens, first-run setup card
- Streaming: play, ±10s, audio track switch (re-negotiates at position), quality options, side-loaded subtitle selection, resume (local and server position)
- Offline: download → play from SAF URI with no media permission; Season screen switching to downloads-only offline and back
- Download queue: Next N from next-up, pause/resume, move to top/up/down, cancel, encode-while-copying, resume after app restart, 10-item batch
- Kid Mode: preset applied in one go; gear/search/download/storage controls hidden; long-press title → PIN gate (wrong PIN refused, right PIN admits); "Set a PIN" chip when no PIN
- Screen time: Episodes in a Row = 1 holds back the next episode and shows "Time for a break!"; grown-up override (PIN → One more episode) plays it
- Offline playback sync: played offline in airplane mode → `PlaybackSyncWorker` queued → on reconnect, one `POST UserItems/{id}/UserData` and the server shows it watched
- Connection recovery: airplane mode on the home screen → Offline chip + downloads-only tiles; off → real home back in ~6s with no stuck banner
- Player gestures: double-tap right/left seeks ±10s with controls showing and (right half) hidden; vertical swipes show "Volume N%" / "Brightness N%" with controls hidden and do nothing with them showing; the window brightness override is released on exit
- Player idle: paused → dims to 0.05 after 30s, keep-screen-on flag dropped by 45s, a touch restores both
- Trickplay: dragging the seek bar shows a thumbnail with its timestamp
- Up Next: card shows "S01E02 · title" with the real time remaining; Cancel keeps the player on the ended episode and suppresses the card for that player session; with no cancel, the next episode loads when the countdown ends
- Skip Intro / Skip Credits (a series with server segments): Skip Intro appears bottom-end during the title sequence and jumps past it; the credits-marker Up Next card appears ~2 min before the end, Skip Credits is hidden while it shows and returns after Cancel
- Headset/Bluetooth media buttons: confirmed working on a real device by the user
- Ready on Press: the inbox screen lists the finished file (title, preset, size); tapping download flips the row to "In progress" and the file lands on the device with **no new Press job** (job count unchanged), then plays offline. The Downloads tile shows the thumbnail mosaic once something is downloaded
- Failing download: Press URL set to a dead port → Test Connection says "Unreachable"; queued items go FAILED ("Transcode failed · 720p"), the first immediately and the second after the ~30s backoff; Downloads shows a Failed section with per-item Retry, Retry All and Clear. With the URL restored, Retry All re-ran both and they completed
- Simultaneous Downloads = 2: a 10-item batch never had more than 2 jobs `running` on Press at once (polled), and the queue drained
- Completion notification: grouped "Download complete" entries; with the app in the background, tapping one opens the Downloads screen
- Storage screen: Largest/Newest/Oldest/Name sorts order correctly; Delete Oldest defaults to 5 and its "freeing N MB" figure matched the five oldest items exactly, the files were removed from the download folder and the totals dropped by the same amount; Delete Everything asks first, then leaves 0 files on disk and "No downloads on device"

- Portrait / rotation: with auto-rotate off, `settings put system user_rotation 1` (portrait) / `0` (landscape). Search results, home (rows scroll, tiles reflow to two columns), Movies grid (five columns, chips scroll), series Detail, movie Detail (poster + title + actions, then the info panel) and the Season screen (blurred-poster backdrop, hero, rows fit) all lay out cleanly. Rotating mid-playback in both directions keeps playing and re-lays-out the controls; rotating back lands in the right layout
- Left-half double-tap seek (controls hidden): shows a "−10s" readout and the position moves back 10s (00:27 → 00:17)
- My List: favoriting a movie with the heart on Detail makes a My List row appear on the home screen with that movie; unfavoriting removes the row again
- Failure notification: with a worker running, deleting its job on Press gives "Download failed · <title>" in the shade and a FAILED item (Retry button) in the app
- Season manage menu (2026-11-18): folder icon appears only once an episode is finished; move to the other folder relocates every downloaded episode (files verified on disk, menu then offers the original folder); delete asks first and removes the files
- Browsing downloads (2026-11-18): episodes group under an expandable series row, Largest/A–Z/Recent/Oldest reorder, Movies/TV/folder filters, "No downloads match" + Clear filters, search "bluey" opens the matching series. Not tried: Kid Mode, Unwatched/Watched chips with real watch state
- Ready on Press browsing (2026-11-18): grouped series rows with a one-tap Download season (all 20 episodes of a small season landed in ~20s with no new Press jobs; header flipped to "all downloaded"), Not downloaded / Movies filters, A–Z and Largest sorts, search "sofia" opens the series with its season button
- Downloads management (2026-11-18): series → season grouping with compact episode cards; series ⋮ move of all 20 episodes (files verified in the target folder, spinner while moving); season ⋮ Delete all confirms with count/size and the deletion survives a relaunch; "Delete these 1" under the TV filter, "No downloads match" + Clear filters; storage panel expands (summary + per-folder bars); in-progress episodes of one series group as "5 in progress · 75%" and the X cancels them all; series poster matches Ready on Press; Ready on Press Elena batch (27.8 GB vs ~6.8 GB free) shows the red not-enough-room warning. Not tried: Kid Mode, Delete watched / Delete oldest… buttons, queued (not yet running) grouping, Season-screen bulk confirm
- Cancel mid-copy (2026-11-18): started an episode, cancelled it at 53% from Downloads → the partial `.mp4` was gone from disk within 2s and the row cleared

## Driving the player from adb (what worked)
- A double-tap needs two `input tap`s ~100ms apart: `adb shell "input tap X Y & sleep 0.1; input tap X Y & wait"`. Chained `input tap; input tap` is too slow (each is a JVM start) and registers as two single taps that toggle the controls. `sendevent` is permission-denied on this image
- Gesture surface exists only while controls are hidden; check the play button pixel at (1280,800) (white = controls showing) before swiping
- Seek-bar taps don't seek; drag from the knob (`input swipe 150 1482 X 1482 3000`) right after showing the controls. `KEYCODE_MEDIA_FAST_FORWARD` is ~+10s per press
- Capture mid-gesture with `adb shell "input swipe … & sleep 0.45; screencap -p /sdcard/x.png; wait"`
- Read the brightness override with `dumpsys display | grep mWindowManagerBrightnessOverride` (NaN = none); the keep-screen-on flag is `KEEP_SCREEN_ON` in the app window's attrs in `dumpsys window windows`

## Not yet tested
### From the first four areas (partly done)
- Daily Screen Time limit: the 5-minutes-left warning, a title opened with the budget already spent never starting, +30 min / +1 h grants, day rollover. Needs ≥30 min of real playback, or a shorter limit added for testing
- Offline sync conflict rule: server `LastPlayedDate` newer than the offline viewing → server wins and the row is dropped. Needs a second client
- Jellyfin reachable network but server down (the "unreachable — retrying…" banner and backoff). The emulator image isn't rootable, so this needs Jellyfin itself stopped briefly
- 401 handling: a revoked token should show "sign-in expired" + Sign In, not "unreachable", and stop retrying. Needs a session revoked in the Jellyfin dashboard (the user then has to sign in again)
- Per-install DeviceId: two installs on one account must not log each other out. Needs a second device/emulator

### Player
- Forced subtitles auto-enabled for downloaded files (`tracksJson`) — needs a download first, so do it with the Downloads group
- The screen actually turning off after the idle timeout (needs real hardware)

### Downloads and storage
- Wi-Fi-only toggle (needs a metered/cellular network)
- The failure notification's own tap with a *single* notification in the shade (it goes through the same `postResultNotification()` as the complete one, which does open Downloads, so it is expected to work; I only got to tap it inside a group)


